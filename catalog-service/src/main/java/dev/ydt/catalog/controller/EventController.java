package dev.ydt.catalog.controller;

import dev.ydt.catalog.dto.event.EventPage;
import dev.ydt.catalog.dto.event.EventResponse;
import dev.ydt.catalog.dto.event.EventSearchPage;
import dev.ydt.catalog.service.EventSearchService;
import dev.ydt.catalog.service.EventService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;
    private final EventSearchService eventSearchService;

    @GetMapping
    public EventPage list(@RequestParam(required = false) @Size(max = 100) String city,
                          @RequestParam(required = false) @Size(max = 200) String after,
                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return eventService.findOnSale(city, after, size);
    }

    /** Поиск в Elasticsearch с опечатками: «jaz nigth» найдёт Jazz Night. Только события в продаже. */
    @GetMapping("/search")
    public EventSearchPage search(@RequestParam @NotBlank @Size(max = 200) String q,
                                  @RequestParam(required = false) @Size(max = 100) String city,
                                  @RequestParam(required = false) Instant from,
                                  @RequestParam(required = false) Instant to,
                                  // from + size в Elasticsearch ограничено 10 000 (index.max_result_window)
                                  @RequestParam(defaultValue = "0") @Min(0) @Max(99) int page,
                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return eventSearchService.search(q, city, from, to, page, size);
    }

    @GetMapping("/{id}")
    public EventResponse get(@PathVariable Long id) {
        return eventService.findById(id);
    }
}
