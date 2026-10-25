package dev.ydt.catalog.dto.event;

import dev.ydt.catalog.entity.enums.EventStatus;

import java.time.Instant;
import java.util.List;

public record EventResponse(Long id, String title, String city, String venue, Instant startsAt,
                            Instant salesOpenAt, EventStatus status, List<String> performers) {
}
