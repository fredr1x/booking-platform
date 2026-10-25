package dev.ydt.booking.dto.seat;

import dev.ydt.booking.entity.enums.SeatStatus;

import java.math.BigDecimal;

public record SeatResponse(
        Long id,
        String section,
        String row,
        int number,
        BigDecimal price,
        SeatStatus status
) {
}
