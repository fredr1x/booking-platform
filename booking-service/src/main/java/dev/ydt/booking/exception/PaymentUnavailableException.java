package dev.ydt.booking.exception;

import lombok.Getter;

import java.time.Duration;

/**
 * Платёжка не ответила: таймаут, 5xx, открыт circuit breaker или занят bulkhead.
 * Не BookingException: ответу нужен заголовок Retry-After, поэтому у него свой обработчик.
 */
@Getter
public class PaymentUnavailableException extends RuntimeException {

    private final Duration retryAfter;

    public PaymentUnavailableException(Duration retryAfter, Throwable cause) {
        super("Payment service is unavailable, retry later", cause);
        this.retryAfter = retryAfter;
    }
}
