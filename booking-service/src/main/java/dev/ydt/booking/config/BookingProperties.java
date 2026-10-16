package dev.ydt.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties("booking")
public record BookingProperties(Duration holdTtl, Pricing pricing) {

    /**
     * @param earlyBirdWindow   сколько после открытия продаж действует скидка
     * @param earlyBirdDiscount доля скидки, 0.15 = 15%
     * @param demandThreshold   заполненность зала, с которой включается наценка, 0.8 = 80%
     * @param demandMaxMarkup   наценка при полностью проданном зале, 0.5 = +50%
     */
    public record Pricing(Duration earlyBirdWindow, BigDecimal earlyBirdDiscount,
                          BigDecimal demandThreshold, BigDecimal demandMaxMarkup) {
    }
}
