package dev.ydt.booking.dto.booking;

import dev.ydt.booking.entity.enums.BookingStatus;
import dev.ydt.booking.entity.enums.CancelReason;
import dev.ydt.booking.entity.enums.PricingType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BookingResponse(
        Long id,
        Long eventId,
        String userId,
        BookingStatus status,
        PricingType pricingType,
        BigDecimal totalPrice,
        Instant expiresAt,
        String paymentId,
        Instant confirmedAt,
        CancelReason cancelReason,
        Instant closedAt,
        Instant createdAt,
        List<BookingSeatResponse> seats
) {
}
