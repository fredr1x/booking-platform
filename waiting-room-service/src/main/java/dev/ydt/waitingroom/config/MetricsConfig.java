package dev.ydt.waitingroom.config;

import dev.ydt.waitingroom.repository.QueueRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    /** queue_length{event} — сколько человек ждёт в очереди. Считается при каждом опросе Prometheus (ZCARD, O(1)). */
    @Bean
    public MeterBinder queueLength(QueueRepository queueRepository, QueueProperties properties) {
        return registry -> properties.eventIds().forEach(eventId ->
                Gauge.builder("queue.length", () -> {
                            try {
                                return queueRepository.size(eventId);
                            } catch (RuntimeException e) {
                                // Redis недоступен: пропуск точки на графике, а не ошибка в /actuator/prometheus
                                return Double.NaN;
                            }
                        })
                        .tag("event", String.valueOf(eventId))
                        .register(registry));
    }
}
