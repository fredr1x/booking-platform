package dev.ydt.booking.service.pricing;

import dev.ydt.booking.dto.event.CatalogEvent;
import dev.ydt.booking.entity.Seat;
import dev.ydt.booking.entity.enums.SeatStatus;
import dev.ydt.booking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PricingService {

    // Spring внедряет список, отсортированный по @Order
    private final List<PricingStrategy> strategies;
    private final SeatRepository seatRepository;

    public PriceQuote quote(CatalogEvent event, List<Seat> seats, Instant now) {
        PricingContext context = context(event, now);
        PricingStrategy strategy = strategies.stream()
                .filter(s -> s.supports(context))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No pricing strategy for " + context));

        Map<Long, BigDecimal> prices = new LinkedHashMap<>();
        for (Seat seat : seats) {
            prices.put(seat.getId(), strategy.price(seat.getPrice(), context));
        }
        return new PriceQuote(strategy.type(), prices);
    }

    private PricingContext context(CatalogEvent event, Instant now) {
        long total = 0;
        long available = 0;
        for (SeatRepository.StatusCount count : seatRepository.countByStatus(event.id())) {
            total += count.getCount();
            if (count.getStatus() == SeatStatus.AVAILABLE) {
                available = count.getCount();
            }
        }
        return new PricingContext(now, event.salesOpenAt(), total, total - available);
    }
}
