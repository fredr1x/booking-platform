package dev.ydt.booking.service.pricing;

import dev.ydt.booking.entity.enums.PricingType;

import java.math.BigDecimal;
import java.util.Map;

/**
 * @param seatPrices итоговая цена по id места
 */
public record PriceQuote(PricingType type, Map<Long, BigDecimal> seatPrices) {

    public PriceQuote {
        seatPrices = Map.copyOf(seatPrices);
    }

    public BigDecimal total() {
        return seatPrices.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
