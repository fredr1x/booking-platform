package dev.ydt.booking.exception;

import lombok.Getter;

import java.util.List;

@Getter
public final class SeatUnavailableException extends BookingException {

    private final List<Long> seatIds;

    public SeatUnavailableException(List<Long> seatIds) {
        super("Seats " + seatIds + " are not available");
        this.seatIds = List.copyOf(seatIds);
    }
}
