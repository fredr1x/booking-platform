package dev.ydt.catalog.dto.event;

import dev.ydt.catalog.exception.InvalidCursorException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Позиция в ленте: последнее показанное событие. Клиенту отдаётся непрозрачной строкой,
 * чтобы формат можно было менять, не ломая клиентов.
 */
public record EventCursor(
        Instant startsAt,
        long id
) {

    public String encode() {
        String raw = startsAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    // курсор приходит от клиента, поэтому любой мусор превращаем в 400, а не в 500
    public static EventCursor decode(String cursor) {
        try {
            var raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            return new EventCursor(Instant.parse(raw.substring(0, separator)),
                    Long.parseLong(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new InvalidCursorException(cursor);
        }
    }
}
