package dev.ydt.booking.service.pricing;

import dev.ydt.booking.config.BookingProperties;
import dev.ydt.booking.entity.enums.PricingType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Скидка в первые часы после открытия продаж. */
@Component
@Order(2)
public class EarlyBirdPricing implements PricingStrategy {

    private final BookingProperties.Pricing config;

    public EarlyBirdPricing(BookingProperties properties) {
        this.config = properties.pricing();
    }

    @Override
    public PricingType type() {
        return PricingType.EARLY_BIRD;
    }

    @Override
    public boolean supports(PricingContext context) {
        return context.salesOpenAt() != null
                && context.now().isBefore(context.salesOpenAt().plus(config.earlyBirdWindow()));
    }

    @Override
    public BigDecimal price(BigDecimal basePrice, PricingContext context) {
        return basePrice.multiply(BigDecimal.ONE.subtract(config.earlyBirdDiscount()))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
