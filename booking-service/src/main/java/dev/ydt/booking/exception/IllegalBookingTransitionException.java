package dev.ydt.booking.exception;

import dev.ydt.booking.entity.enums.BookingStatus;
import dev.ydt.booking.entity.state.BookingAction;
import lombok.Getter;

@Getter
public final class IllegalBookingTransitionException extends BookingException {

    private final BookingStatus from;
    private final BookingAction action;

    public IllegalBookingTransitionException(BookingStatus from, BookingAction action) {
        this(from, action, null);
    }

    public IllegalBookingTransitionException(BookingStatus from, BookingAction action, String detail) {
        super("Cannot " + action + " booking in status " + from + (detail == null ? "" : ": " + detail));
        this.from = from;
        this.action = action;
    }
}
