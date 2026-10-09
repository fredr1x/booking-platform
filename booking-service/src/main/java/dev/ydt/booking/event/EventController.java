package dev.ydt.booking.event;

import dev.ydt.booking.seat.Seat;
import dev.ydt.booking.seat.SeatRepository;
import dev.ydt.booking.seat.SeatStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// Простые чтения для проверки скелета. Слои и глобальный обработчик ошибок появятся в блоке 4.
@RestController
@RequestMapping("/api/v1/events")
@Transactional(readOnly = true)
public class EventController {

    private final EventRepository events;
    private final SeatRepository seats;

    public EventController(EventRepository events, SeatRepository seats) {
        this.events = events;
        this.seats = seats;
    }

    @GetMapping
    public List<EventResponse> list() {
        return events.findAll(Sort.by("startsAt")).stream().map(EventResponse::from).toList();
    }

    @GetMapping("/{id}")
    public EventResponse get(@PathVariable Long id) {
        return EventResponse.from(findEvent(id));
    }

    @GetMapping("/{id}/availability")
    public Availability availability(@PathVariable Long id) {
        findEvent(id);
        Map<SeatStatus, Long> counts = new EnumMap<>(SeatStatus.class);
        for (SeatStatus status : SeatStatus.values()) {
            counts.put(status, 0L);
        }
        seats.countByStatus(id).forEach(c -> counts.put(c.getStatus(), c.getCount()));
        return new Availability(id, counts);
    }

    @GetMapping("/{id}/seats")
    public List<SeatResponse> seats(@PathVariable Long id,
                                    @RequestParam(defaultValue = "AVAILABLE") SeatStatus status,
                                    @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        findEvent(id);
        return seats.findByEventIdAndStatusOrderByIdAsc(id, status, Limit.of(limit))
                .stream().map(SeatResponse::from).toList();
    }

    private Event findEvent(Long id) {
        return events.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event " + id + " not found"));
    }

    public record EventResponse(Long id, String title, String venue, Instant startsAt,
                                Instant salesOpenAt, EventStatus status) {
        static EventResponse from(Event e) {
            return new EventResponse(e.getId(), e.getTitle(), e.getVenue(), e.getStartsAt(),
                    e.getSalesOpenAt(), e.getStatus());
        }
    }

    public record SeatResponse(Long id, String section, String row, int number, BigDecimal price, SeatStatus status) {
        static SeatResponse from(Seat s) {
            return new SeatResponse(s.getId(), s.getSection(), s.getRowLabel(), s.getSeatNumber(),
                    s.getPrice(), s.getStatus());
        }
    }

    public record Availability(Long eventId, Map<SeatStatus, Long> seats) {
    }
}
