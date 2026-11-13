package dev.ydt.waitingroom.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Set;

/**
 * @param eventIds     события с очередью: на чужой eventId ключи в Redis не заводим
 * @param admitPerTick сколько человек пропускать за один проход
 * @param admitInterval пауза между проходами
 * @param admissionTtl сколько действует пропуск
 * @param tokenSecret  общий секрет с booking-service, короткий секрет не даст стартовать
 */
// shortcut: список событий в настройках; появится API каталога — флаг flash sale у самого события
@Validated
@ConfigurationProperties("queue")
public record QueueProperties(
        @NotEmpty Set<Long> eventIds,
        @Min(1) int admitPerTick,
        Duration admitInterval,
        Duration admissionTtl,
        @Size(min = 32) String tokenSecret
) {
}
