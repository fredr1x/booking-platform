package dev.ydt.booking.scheduler;

import dev.ydt.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Истечение холдов: точно в срок по очереди в Redis и редкий запасной обход таблицы через SKIP LOCKED (BookingRepository).
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpirationScheduler {

    private final BookingService bookingService;

    @Scheduled(fixedDelayString = "${booking.hold-expiration-poll-interval:1s}")
    public void expireDueHolds() {
        int expired = bookingService.expireDueHolds();
        if (expired > 0) {
            log.info("Expired {} bookings from the Redis queue", expired);
        }
    }

    @Scheduled(fixedDelayString = "${booking.expiration-sweep-interval:5m}")
    public void expireOverdue() {
        int expired = bookingService.expireOverdue();
        if (expired > 0) {
            log.info("Expired {} overdue bookings", expired);
        }
    }

    @Scheduled(fixedDelayString = "PT1H")
    public void purgeIdempotencyKeys() {
        int purged = bookingService.purgeIdempotencyKeys();
        if (purged > 0) {
            log.info("Purged {} old idempotency keys", purged);
        }
        int processed = bookingService.purgeProcessedMessages();
        if (processed > 0) {
            log.info("Purged {} old processed message ids", processed);
        }
    }
}
