package dev.ydt.booking.scheduler;

import dev.ydt.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Простой уборщик через БД. В блоке 3 добавим SKIP LOCKED для нескольких инстансов, в блоке 7 холды переедут в Redis.
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpirationScheduler {

    private final BookingService bookingService;

    @Scheduled(fixedDelayString = "${booking.expiration-sweep-interval:PT30S}")
    public void expireOverdue() {
        int expired = bookingService.expireOverdue();
        if (expired > 0) {
            log.info("Expired {} overdue bookings", expired);
        }
    }
}
