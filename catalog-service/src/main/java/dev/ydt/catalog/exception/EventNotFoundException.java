package dev.ydt.catalog.exception;

public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(Long eventId) {
        super("Event " + eventId + " not found");
    }
}
