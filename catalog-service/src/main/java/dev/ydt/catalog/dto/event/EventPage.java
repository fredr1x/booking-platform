package dev.ydt.catalog.dto.event;

import java.util.List;

/** @param nextCursor передать в {@code after} за следующей страницей; null, если страница последняя */
public record EventPage(List<EventResponse> items, String nextCursor) {
}
