package dev.ydt.catalog.controller;

import dev.ydt.catalog.dto.event.EventPage;
import dev.ydt.catalog.dto.event.EventResponse;
import dev.ydt.catalog.service.EventService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping
    public EventPage list(@RequestParam(required = false) @Size(max = 100) String city,
                          @RequestParam(required = false) @Size(max = 200) String after,
                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return eventService.findOnSale(city, after, size);
    }

    @GetMapping("/{id}")
    public EventResponse get(@PathVariable Long id) {
        return eventService.findById(id);
    }
}
