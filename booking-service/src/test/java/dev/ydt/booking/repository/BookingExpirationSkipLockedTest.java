package dev.ydt.booking.repository;

import dev.ydt.booking.entity.Booking;
import dev.ydt.booking.entity.enums.BookingStatus;
import dev.ydt.booking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Два уборщика одновременно: второй не ждёт первого и не берёт его брони (P2). */
class BookingExpirationSkipLockedTest extends AbstractIntegrationTest {

    @Autowired
    BookingRepository bookingRepository;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void secondSweeperSkipsBookingsLockedByFirst() throws Exception {
        long bookingId = overduePendingBooking();
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);

        CompletableFuture<List<Long>> first = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            List<Long> ids = sweep();
            firstLocked.countDown();
            await(secondDone); // держим блокировки, пока второй уборщик не отработает
            return ids;
        }));
        firstLocked.await(10, TimeUnit.SECONDS);
        List<Long> second = tx.execute(status -> sweep());
        secondDone.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS)).contains(bookingId);
        assertThat(second).doesNotContain(bookingId);
    }

    private List<Long> sweep() {
        return bookingRepository.findByStatusAndExpiresAtBefore(BookingStatus.PENDING, Instant.now(), Limit.of(500))
                .stream().map(Booking::getId).toList();
    }

    // Бронь без мест: уборщику важны только статус и срок.
    private long overduePendingBooking() {
        return jdbc.queryForObject("""
                INSERT INTO bookings (event_id, user_id, status, pricing_type, total_price, expires_at, created_at, updated_at)
                VALUES (1, 'sweeper-test', 'PENDING', 'STANDARD', 0, now() - interval '1 minute', now(), now())
                RETURNING id
                """, Long.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
