package dev.ydt.booking.exception;

public final class EventNotFoundException extends BookingException {

    public EventNotFoundException(Long eventId) {
        super("Event " + eventId + " not found");
    }
}
