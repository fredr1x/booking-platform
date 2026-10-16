package dev.ydt.booking.exception;

public final class BookingNotFoundException extends BookingException {

    public BookingNotFoundException(Long bookingId) {
        super("Booking " + bookingId + " not found");
    }
}
