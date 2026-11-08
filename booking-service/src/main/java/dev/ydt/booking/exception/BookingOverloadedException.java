package dev.ydt.booking.exception;

/** Не BookingException: ответу нужен заголовок Retry-After, поэтому у него свой обработчик. */
public class BookingOverloadedException extends RuntimeException {

    public static final int RETRY_AFTER_SECONDS = 1;

    public BookingOverloadedException() {
        super("Too many bookings in progress, retry later");
    }
}
