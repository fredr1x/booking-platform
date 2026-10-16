package dev.ydt.booking.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Данные, от которых зависит цена.
 *
 * @param takenSeats места в статусах HELD и BOOKED
 */
public record PricingContext(Instant now, Instant salesOpenAt, long totalSeats, long takenSeats) {

    public BigDecimal occupancy() {
        if (totalSeats == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(takenSeats).divide(BigDecimal.valueOf(totalSeats), 4, RoundingMode.HALF_UP);
    }
}
