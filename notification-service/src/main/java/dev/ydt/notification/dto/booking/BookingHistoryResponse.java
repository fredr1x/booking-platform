package dev.ydt.notification.dto.booking;

import dev.ydt.notification.entity.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record BookingHistoryResponse(
        Long id,
        Long eventId,
        BigDecimal totalPrice,
        BookingStatus status,
        String paymentId,
        String cancelReason,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {
}
