package dev.ydt.booking.contracts.payment;

import java.math.BigDecimal;

/** Тело {@code POST /api/v1/payments}. Id платежа идёт заголовком {@code Idempotency-Key}. */
public record AuthorizePaymentRequest(
        Long bookingId,
        String userId,
        BigDecimal amount
) {
}
