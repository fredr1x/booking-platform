package dev.ydt.booking.service.pricing;

import dev.ydt.booking.dto.event.CatalogEvent;
import dev.ydt.booking.entity.Seat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PricingService {

    // Spring внедряет список, отсортированный по @Order
    private final List<PricingStrategy> strategies;
    private final OccupancyService occupancyService;

    public PriceQuote quote(CatalogEvent event, List<Seat> seats, Instant now) {
        PricingContext context = context(event, now);
        PricingStrategy strategy = strategies.stream()
                .filter(s -> s.supports(context))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No pricing strategy for " + context));

        var prices = new LinkedHashMap<Long, BigDecimal>();
        for (Seat seat : seats) {
            prices.put(seat.getId(), strategy.price(seat.getPrice(), context));
        }
        return new PriceQuote(strategy.type(), prices);
    }

    private PricingContext context(CatalogEvent event, Instant now) {
        Occupancy occupancy = occupancyService.occupancy(event.id());
        return new PricingContext(now, event.salesOpenAt(), occupancy.totalSeats(), occupancy.takenSeats());
    }
}
