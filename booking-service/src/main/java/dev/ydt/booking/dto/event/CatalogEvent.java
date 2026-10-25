package dev.ydt.booking.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.ydt.booking.entity.enums.EventStatus;

import java.time.Instant;

/** То, что бронированию нужно знать о событии из catalog-service. Остальные поля ответа каталога не читаем. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogEvent(
        Long id,
        EventStatus status,
        Instant salesOpenAt
) {
}
