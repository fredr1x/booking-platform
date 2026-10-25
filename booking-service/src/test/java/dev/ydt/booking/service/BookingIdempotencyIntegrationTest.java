package dev.ydt.booking.service;

import dev.ydt.booking.dto.booking.BookingResponse;
import dev.ydt.booking.dto.booking.CreateBookingRequest;
import dev.ydt.booking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Клиент не дождался ответа и шлёт тот же запрос ещё раз, пока первый ещё выполняется.
 * Без ключа второй получил бы 409 на своё же место, с ключом оба получают одну и ту же бронь.
 */
class BookingIdempotencyIntegrationTest extends AbstractIntegrationTest {

    private static final int DUPLICATES = 50;

    @Autowired
    BookingService bookingService;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void concurrentDuplicatesCreateOneBooking() throws Exception {
        Long seatId = jdbc.queryForObject(
                "SELECT id FROM seats WHERE event_id = 1 AND status = 'AVAILABLE' ORDER BY id LIMIT 1", Long.class);
        var request = new CreateBookingRequest(1L, List.of(seatId));
        var start = new CountDownLatch(1);

        List<Long> ids;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<BookingResponse>> results = IntStream.range(0, DUPLICATES)
                    .mapToObj(i -> executor.submit(() -> {
                        start.await();
                        return bookingService.create("impatient", "retry-storm", request);
                    }))
                    .toList();
            start.countDown();
            ids = results.stream().map(BookingIdempotencyIntegrationTest::idOf).toList();
        }

        assertThat(ids).hasSize(DUPLICATES).containsOnly(ids.getFirst());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE user_id = 'impatient'", Long.class))
                .isEqualTo(1);
    }

    private static Long idOf(Future<BookingResponse> result) {
        try {
            return result.get().id();
        } catch (Exception e) {
            throw new AssertionError("duplicate request failed instead of replaying", e);
        }
    }
}
