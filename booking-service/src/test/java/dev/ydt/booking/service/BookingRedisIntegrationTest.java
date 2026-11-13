package dev.ydt.booking.service;

import dev.ydt.booking.dto.booking.BookingResponse;
import dev.ydt.booking.dto.booking.CreateBookingRequest;
import dev.ydt.booking.exception.SeatUnavailableException;
import dev.ydt.booking.service.locking.SeatLockingMode;
import dev.ydt.booking.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Claim мест и очередь истечения холдов в Redis поверх той же брони в PostgreSQL. */
class BookingRedisIntegrationTest extends AbstractIntegrationTest {

    private static final int BUYERS = 200;

    @Autowired
    BookingService bookingService;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    MeterRegistry meterRegistry;
    @Autowired
    SeatClaimService seatClaimService;

    @Test
    void losersOfOneSeatAreRejectedInRedisBeforeTheDatabase() throws Exception {
        long seat = freeSeat();
        double rejectedBefore = rejected();
        var start = new CountDownLatch(1);
        var succeeded = new AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < BUYERS; i++) {
                int buyer = i;
                executor.submit(() -> {
                    start.await();
                    bookingService.create("redis-buyer-" + buyer, new CreateBookingRequest(1L, List.of(seat)),
                            SeatLockingMode.REDIS);
                    succeeded.incrementAndGet();
                    return null;
                });
            }
            start.countDown();
        }

        assertThat(succeeded).hasValue(1);
        assertThat(rejected() - rejectedBefore).as("до БД дошёл один покупатель").isEqualTo(BUYERS - 1);
    }

    @Test
    void cancelledSeatCanBeBookedAgainRightAway() {
        long seat = freeSeat();
        BookingResponse first = bookingService.create("alice", new CreateBookingRequest(1L, List.of(seat)));
        assertThatThrownBy(() -> bookingService.create("bob", new CreateBookingRequest(1L, List.of(seat))))
                .isInstanceOf(SeatUnavailableException.class);

        bookingService.cancel(first.id(), "alice");

        assertThat(bookingService.create("bob", new CreateBookingRequest(1L, List.of(seat))).status().name())
                .isEqualTo("PENDING");
    }

    @Test
    void holdExpiresFromRedisQueue() {
        long seat = freeSeat();
        BookingResponse booking = bookingService.create("carol", new CreateBookingRequest(1L, List.of(seat)));
        // срок вышел: в БД и в очереди
        Instant past = Instant.now().minusSeconds(1);
        jdbc.update("UPDATE bookings SET expires_at = ? WHERE id = ?", Timestamp.from(past), booking.id());
        redis.opsForZSet().add("hold-expirations", booking.id().toString(), past.toEpochMilli());

        assertThat(bookingService.expireDueHolds()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, booking.id()))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id = ?", String.class, seat))
                .isEqualTo("AVAILABLE");
        assertThat(redis.hasKey("seat:{1}:" + seat)).as("claim снят вместе с холдом").isFalse();
        assertThat(bookingService.expireDueHolds()).as("из очереди бронь забрали один раз").isZero();
    }

    @Test
    void claimIsAllOrNothing() {
        var ttl = Duration.ofMinutes(1);
        seatClaimService.claim(77L, List.of(2L), "alice", ttl);

        assertThatThrownBy(() -> seatClaimService.claim(77L, List.of(1L, 2L, 3L), "bob", ttl))
                .isInstanceOfSatisfying(SeatUnavailableException.class,
                        e -> assertThat(e.getSeatIds()).containsExactly(2L));

        assertThat(redis.hasKey("seat:{77}:1")).as("свободные места проигравший не занял").isFalse();
        assertThat(redis.hasKey("seat:{77}:3")).isFalse();
        assertThat(seatClaimService.claim(77L, List.of(2L, 4L), "alice", ttl))
                .as("своё место проходит, снимать при откате надо только новые ключи")
                .containsExactly("seat:{77}:4");
    }

    private long freeSeat() {
        // с конца списка: другие тесты берут места события 1 с начала
        return jdbc.queryForObject(
                "SELECT id FROM seats WHERE event_id = 1 AND status = 'AVAILABLE' ORDER BY id DESC OFFSET 50 LIMIT 1",
                Long.class);
    }

    private double rejected() {
        return meterRegistry.counter("booking.seat.claim.rejected").count();
    }
}
