package dev.ydt.booking.service.locking;

import dev.ydt.booking.BookingServiceApplication;
import dev.ydt.booking.dto.booking.CreateBookingRequest;
import dev.ydt.booking.exception.SeatUnavailableException;
import dev.ydt.booking.service.BookingService;
import dev.ydt.booking.support.AbstractIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тысяча виртуальных потоков одновременно бронирует места через настоящий BookingService и PostgreSQL.
 * Цифры из логов этого теста лежат в docs/adr/0001-seat-locking.md.
 */
@Slf4j
class SeatRaceIntegrationTest extends AbstractIntegrationTest {

    // Flash Sale Mega Concert: 20 000 мест, другие тесты его не трогают
    private static final long EVENT_ID = 2;
    private static final int THREADS = 1000;
    private static final String SAFETY_NET_INDEX = "uq_booking_seats_active_seat";

    private static final Map<String, String> REPORT = new ConcurrentHashMap<>();

    @Autowired
    BookingService bookingService;
    @Autowired
    JdbcTemplate jdbc;

    @ParameterizedTest(name = "{0}: 1000 потоков на одно место, продано ровно одно")
    @EnumSource(value = SeatLockingMode.class, names = "NAIVE", mode = EnumSource.Mode.EXCLUDE)
    void oneSeatIsSoldExactlyOnce(SeatLockingMode mode) throws Exception {
        long seat = freeSeats(1).getFirst();

        RaceResult result = race(mode, i -> seat, i -> bookingService);

        report(mode + " / одно место", result);
        assertThat(result.errors()).isZero();
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(activeClaims(seat)).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}: 1000 потоков на разные места, все проданы")
    @EnumSource(value = SeatLockingMode.class, names = "NAIVE", mode = EnumSource.Mode.EXCLUDE)
    void distinctSeatsAreAllSold(SeatLockingMode mode) throws Exception {
        List<Long> seats = freeSeats(THREADS);

        RaceResult result = race(mode, seats::get, i -> bookingService);

        report(mode + " / разные места", result);
        assertThat(result.errors()).isZero();
        assertThat(result.succeeded()).isEqualTo(THREADS);
    }

    @Test
    void naiveSellsSeatTwiceWithoutSafetyNet() throws Exception {
        long seat = freeSeats(1).getFirst();

        RaceResult result = withoutSafetyNet(seat,
                () -> race(SeatLockingMode.NAIVE, i -> seat, i -> bookingService));

        report("NAIVE / одно место, без индекса", result);
        assertThat(result.succeeded()).as("check-then-act продаёт одно место несколько раз").isGreaterThan(1);
    }

    @Test
    void safetyNetIndexCatchesNaiveRace() throws Exception {
        long seat = freeSeats(1).getFirst();

        RaceResult result = race(SeatLockingMode.NAIVE, i -> seat, i -> bookingService);

        report("NAIVE / одно место, с индексом", result);
        assertThat(result.errors()).isZero();
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(activeClaims(seat)).isEqualTo(1);
    }

    @Test
    void jvmLockSellsSeatTwiceOnTwoInstances() throws Exception {
        long seat = freeSeats(1).getFirst();

        // второй инстанс сервиса: отдельный Spring-контекст со своими блокировками в памяти, та же база
        try (ConfigurableApplicationContext secondInstance = new SpringApplicationBuilder(BookingServiceApplication.class)
                .web(WebApplicationType.NONE)
                // аргументы командной строки, а не .properties(): у тех приоритет ниже application.yml
                .run("--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.datasource.hikari.connection-timeout=60000",
                        "--booking.scheduling.enabled=false",
                        "--catalog.url=" + catalogUrl())) {
            BookingService other = secondInstance.getBean(BookingService.class);

            RaceResult result = withoutSafetyNet(seat, () -> race(SeatLockingMode.JVM_LOCK, i -> seat,
                    i -> i % 2 == 0 ? bookingService : other));

            report("JVM_LOCK / одно место, 2 инстанса, без индекса", result);
            assertThat(result.succeeded()).as("блокировка в памяти не видна второму инстансу").isGreaterThan(1);
        }
    }

    @AfterAll
    static void printReport() {
        var table = new StringBuilder("\n| Сценарий | Успешно | Конфликтов | Ошибок | Время, мс |\n|---|---|---|---|---|\n");
        REPORT.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> table.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n"));
        log.info("Seat race report:{}", table);
    }

    private RaceResult race(SeatLockingMode mode, IntFunction<Long> seatOf, IntFunction<BookingService> serviceOf)
            throws InterruptedException {
        var start = new CountDownLatch(1);
        var succeeded = new AtomicInteger();
        var conflicts = new AtomicInteger();
        var errors = new AtomicInteger();

        long startedAt;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < THREADS; i++) {
                int thread = i;
                executor.submit(() -> {
                    start.await();
                    try {
                        serviceOf.apply(thread).create("user-" + thread,
                                new CreateBookingRequest(EVENT_ID, List.of(seatOf.apply(thread))), mode);
                        succeeded.incrementAndGet();
                    } catch (SeatUnavailableException | OptimisticLockingFailureException
                             | DataIntegrityViolationException e) {
                        conflicts.incrementAndGet();
                    } catch (RuntimeException e) {
                        if (errors.incrementAndGet() == 1) {
                            log.error("Unexpected error in {} race", mode, e);
                        }
                    }
                    return null;
                });
            }
            startedAt = System.nanoTime();
            start.countDown();
        } // close() ждёт завершения всех задач
        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        return new RaceResult(succeeded.get(), conflicts.get(), errors.get(), millis);
    }

    // Убирает индекс-страховку, чтобы увидеть, что на самом деле делает код. После теста чистит двойные
    // продажи (иначе индекс не создать заново) и возвращает индекс.
    private RaceResult withoutSafetyNet(long seat, Race race) throws InterruptedException {
        jdbc.execute("DROP INDEX " + SAFETY_NET_INDEX);
        try {
            return race.run();
        } finally {
            jdbc.update("DELETE FROM bookings WHERE id IN (SELECT booking_id FROM booking_seats WHERE seat_id = ?)", seat);
            jdbc.execute("CREATE UNIQUE INDEX " + SAFETY_NET_INDEX + " ON booking_seats (seat_id) WHERE active");
        }
    }

    private List<Long> freeSeats(int count) {
        return jdbc.queryForList("SELECT id FROM seats WHERE event_id = ? AND status = 'AVAILABLE' ORDER BY id LIMIT ?",
                Long.class, EVENT_ID, count);
    }

    private int activeClaims(long seat) {
        return jdbc.queryForObject("SELECT count(*) FROM booking_seats WHERE seat_id = ? AND active",
                Integer.class, seat);
    }

    private static void report(String scenario, RaceResult r) {
        REPORT.put(scenario, r.succeeded() + " | " + r.conflicts() + " | " + r.errors() + " | " + r.millis());
    }

    private record RaceResult(int succeeded, int conflicts, int errors, long millis) {
    }

    @FunctionalInterface
    private interface Race {
        RaceResult run() throws InterruptedException;
    }
}
