package dev.ydt.booking.dto.booking;

import java.math.BigDecimal;

public record BookingSeatResponse(Long seatId, String section, String row, int number, BigDecimal price) {
}
