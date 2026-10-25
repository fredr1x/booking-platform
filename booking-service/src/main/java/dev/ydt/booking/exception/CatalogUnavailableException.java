package dev.ydt.booking.exception;

public final class CatalogUnavailableException extends BookingException {

    public CatalogUnavailableException(Long eventId, Throwable cause) {
        super("Catalog is unavailable, cannot check event " + eventId + ", retry later", cause);
    }
}
