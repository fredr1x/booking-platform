package dev.ydt.booking.service.pricing;

import dev.ydt.booking.dto.event.CatalogEvent;
import dev.ydt.booking.entity.Seat;
import dev.ydt.booking.entity.enums.EventStatus;
import dev.ydt.booking.entity.enums.PricingType;
import dev.ydt.booking.entity.enums.SeatStatus;
import dev.ydt.booking.repository.SeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static dev.ydt.booking.service.pricing.PricingStrategiesTest.PROPERTIES;
import static dev.ydt.booking.service.pricing.PricingStrategiesTest.SALES_OPEN;
import static dev.ydt.booking.support.TestEntities.event;
import static dev.ydt.booking.support.TestEntities.seat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    private static final Instant IN_EARLY_BIRD = SALES_OPEN.plus(Duration.ofHours(1));
    private static final Instant AFTER_EARLY_BIRD = SALES_OPEN.plus(Duration.ofDays(5));

    @Mock
    SeatRepository seatRepository;

    private PricingService pricingService;
    private CatalogEvent event;
    private Seat seat;

    @BeforeEach
    void setUp() {
        // тот же порядок, что задаёт @Order в приложении
        pricingService = new PricingService(List.of(
                new DemandPricing(PROPERTIES), new EarlyBirdPricing(PROPERTIES), new StandardPricing()),
                new OccupancyService(seatRepository));
        event = event(1L, EventStatus.ON_SALE, SALES_OPEN);
        seat = seat(1L, event, "10000");
    }

    @Test
    void standardPriceAfterEarlyBirdWindow() {
        stubOccupancy(10, 90);

        PriceQuote quote = pricingService.quote(event, List.of(seat), AFTER_EARLY_BIRD);

        assertThat(quote.type()).isEqualTo(PricingType.STANDARD);
        assertThat(quote.total()).isEqualByComparingTo("10000");
    }

    @Test
    void earlyBirdInsideWindow() {
        stubOccupancy(10, 90);

        PriceQuote quote = pricingService.quote(event, List.of(seat), IN_EARLY_BIRD);

        assertThat(quote.type()).isEqualTo(PricingType.EARLY_BIRD);
        assertThat(quote.total()).isEqualByComparingTo("8500");
    }

    @Test
    void demandWinsOverEarlyBird() {
        stubOccupancy(90, 10);

        PriceQuote quote = pricingService.quote(event, List.of(seat), IN_EARLY_BIRD);

        assertThat(quote.type()).isEqualTo(PricingType.DEMAND);
        assertThat(quote.total()).isEqualByComparingTo("12500");
    }

    private void stubOccupancy(long taken, long available) {
        when(seatRepository.countByStatus(1L)).thenReturn(List.of(
                count(SeatStatus.AVAILABLE, available), count(SeatStatus.HELD, taken)));
    }

    private static SeatRepository.StatusCount count(SeatStatus status, long count) {
        return new SeatRepository.StatusCount() {
            @Override
            public SeatStatus getStatus() {
                return status;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }
}
