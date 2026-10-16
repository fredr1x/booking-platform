package dev.ydt.booking.service.pricing;

import dev.ydt.booking.entity.enums.PricingType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class StandardPricing implements PricingStrategy {

    @Override
    public PricingType type() {
        return PricingType.STANDARD;
    }

    @Override
    public boolean supports(PricingContext context) {
        return true;
    }

    @Override
    public BigDecimal price(BigDecimal basePrice, PricingContext context) {
        return basePrice;
    }
}
