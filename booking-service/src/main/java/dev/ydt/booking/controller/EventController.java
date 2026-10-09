package dev.ydt.booking.controller;

import dev.ydt.booking.dto.event.AvailabilityResponse;
import dev.ydt.booking.dto.event.EventResponse;
import dev.ydt.booking.dto.seat.SeatResponse;
import dev.ydt.booking.entity.enums.SeatStatus;
import dev.ydt.booking.service.EventService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping
    public List<EventResponse> list() {
        return eventService.findAll();
    }

    @GetMapping("/{id}")
    public EventResponse get(@PathVariable Long id) {
        return eventService.findById(id);
    }

    @GetMapping("/{id}/availability")
    public AvailabilityResponse availability(@PathVariable Long id) {
        return eventService.getAvailability(id);
    }

    @GetMapping("/{id}/seats")
    public List<SeatResponse> seats(@PathVariable Long id,
                                    @RequestParam(defaultValue = "AVAILABLE") SeatStatus status,
                                    @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return eventService.findSeats(id, status, limit);
    }
}
