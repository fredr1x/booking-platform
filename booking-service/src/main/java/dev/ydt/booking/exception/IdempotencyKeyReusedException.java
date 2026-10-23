package dev.ydt.booking.exception;

public final class IdempotencyKeyReusedException extends BookingException {

    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request body");
    }
}
