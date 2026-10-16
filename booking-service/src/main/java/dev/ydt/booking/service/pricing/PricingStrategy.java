package dev.ydt.booking.service.pricing;

import dev.ydt.booking.entity.enums.PricingType;

import java.math.BigDecimal;

/**
 * Стратегия цены (паттерн Strategy). {@link PricingService} берёт первую подходящую по {@code @Order}.
 */
public interface PricingStrategy {

    PricingType type();

    boolean supports(PricingContext context);

    BigDecimal price(BigDecimal basePrice, PricingContext context);
}
