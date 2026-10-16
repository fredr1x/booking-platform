package dev.ydt.booking.exception;

import dev.ydt.booking.entity.enums.EventStatus;

public final class EventNotOnSaleException extends BookingException {

    public EventNotOnSaleException(Long eventId, EventStatus status) {
        super("Event " + eventId + " is not on sale (status " + status + ")");
    }
}
