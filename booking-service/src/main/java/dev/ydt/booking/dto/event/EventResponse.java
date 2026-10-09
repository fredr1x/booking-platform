package dev.ydt.booking.dto.event;

import dev.ydt.booking.entity.enums.EventStatus;

import java.time.Instant;

public record EventResponse(Long id, String title, String venue, Instant startsAt,
                            Instant salesOpenAt, EventStatus status) {
}
