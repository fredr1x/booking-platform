package dev.ydt.booking.service.pricing;

import dev.ydt.booking.config.BookingProperties;
import dev.ydt.booking.entity.enums.PricingType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Наценка при высоком спросе. Растёт линейно от 0 на пороге заполненности до {@code demandMaxMarkup}
 * при полном зале. Важнее скидки early bird: если зал почти продан, скидку не даём.
 */
@Component
@Order(1)
public class DemandPricing implements PricingStrategy {

    private final BookingProperties.Pricing config;

    public DemandPricing(BookingProperties properties) {
        this.config = properties.pricing();
    }

    @Override
    public PricingType type() {
        return PricingType.DEMAND;
    }

    @Override
    public boolean supports(PricingContext context) {
        return context.occupancy().compareTo(config.demandThreshold()) >= 0;
    }

    @Override
    public BigDecimal price(BigDecimal basePrice, PricingContext context) {
        BigDecimal threshold = config.demandThreshold();
        BigDecimal progress = context.occupancy().subtract(threshold)
                .divide(BigDecimal.ONE.subtract(threshold), 4, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE);
        BigDecimal markup = config.demandMaxMarkup().multiply(progress);
        return basePrice.multiply(BigDecimal.ONE.add(markup)).setScale(2, RoundingMode.HALF_UP);
    }
}
