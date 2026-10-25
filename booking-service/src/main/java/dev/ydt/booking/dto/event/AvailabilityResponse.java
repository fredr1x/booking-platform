package dev.ydt.booking.dto.event;

import dev.ydt.booking.entity.enums.SeatStatus;

import java.util.Map;

public record AvailabilityResponse(
        Long eventId,
        Map<SeatStatus, Long> seats
) {
}
