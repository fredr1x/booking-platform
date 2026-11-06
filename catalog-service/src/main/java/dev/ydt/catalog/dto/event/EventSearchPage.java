package dev.ydt.catalog.dto.event;

import java.util.List;

/** @param total сколько всего событий нашлось; больше 10 000 Elasticsearch точно не считает */
public record EventSearchPage(
        List<EventResponse> items,
        long total
) {
}
