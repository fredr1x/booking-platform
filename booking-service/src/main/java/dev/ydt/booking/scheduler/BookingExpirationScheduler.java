package dev.ydt.booking.scheduler;

import dev.ydt.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Уборщик через БД: инстансы делят работу через SKIP LOCKED (см. BookingRepository). В блоке 7а холды переедут в Redis.
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
