package dev.ydt.booking.exception;

public final class AdmissionRequiredException extends BookingException {

    public AdmissionRequiredException(Long eventId) {
        super("Event " + eventId + " is on flash sale: join the queue and pass a valid admission token");
    }
}
