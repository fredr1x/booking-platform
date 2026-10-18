package dev.ydt.booking.service.pricing;

import dev.ydt.booking.config.BookingProperties;
import dev.ydt.booking.service.locking.SeatLockingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PricingStrategiesTest {

    static final Instant SALES_OPEN = Instant.parse("2026-10-16T10:00:00Z");
    static final BookingProperties PROPERTIES = new BookingProperties(Duration.ofMinutes(10), SeatLockingMode.ATOMIC,
            new BookingProperties.Pricing(Duration.ofHours(72), new BigDecimal("0.15"),
                    new BigDecimal("0.80"), new BigDecimal("0.50")));

    private final EarlyBirdPricing earlyBird = new EarlyBirdPricing(PROPERTIES);
    private final DemandPricing demand = new DemandPricing(PROPERTIES);
    private final StandardPricing standard = new StandardPricing();

    @Test
    void standardKeepsBasePrice() {
        PricingContext context = new PricingContext(SALES_OPEN, SALES_OPEN, 100, 0);

        assertThat(standard.supports(context)).isTrue();
        assertThat(standard.price(new BigDecimal("12000"), context)).isEqualByComparingTo("12000");
    }

    @Test
    void earlyBirdAppliesOnlyInsideWindow() {
        assertThat(earlyBird.supports(context(SALES_OPEN.plus(Duration.ofHours(71)), 0))).isTrue();
        assertThat(earlyBird.supports(context(SALES_OPEN.plus(Duration.ofHours(72)), 0))).isFalse();
        assertThat(earlyBird.supports(new PricingContext(SALES_OPEN, null, 100, 0))).isFalse();
    }

    @Test
    void earlyBirdGivesDiscount() {
        assertThat(earlyBird.price(new BigDecimal("10000"), context(SALES_OPEN, 0))).isEqualByComparingTo("8500.00");
    }

    @ParameterizedTest(name = "занято {0} из 100 → {1}")
    @CsvSource({
            "79, false",
            "80, true",
            "100, true"
    })
    void demandStartsAtThreshold(long taken, boolean supported) {
        assertThat(demand.supports(context(SALES_OPEN, taken))).isEqualTo(supported);
    }

    @ParameterizedTest(name = "занято {0} из 100 → цена {1}")
    @CsvSource({
            "80, 10000.00",
            "90, 12500.00",
            "100, 15000.00"
    })
    void demandMarkupGrowsLinearly(long taken, String expected) {
        assertThat(demand.price(new BigDecimal("10000"), context(SALES_OPEN, taken))).isEqualByComparingTo(expected);
    }

    private static PricingContext context(Instant now, long takenSeats) {
        return new PricingContext(now, SALES_OPEN, 100, takenSeats);
    }
}
