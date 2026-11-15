package dev.ydt.booking.entity.enums;

public enum CancelReason {
    USER_REQUEST,
    PAYMENT_FAILED,
    /** Платёжка не ответила: таймаут, сбой или открыт circuit breaker. Платёж отменён компенсацией. */
    PAYMENT_UNAVAILABLE
}
