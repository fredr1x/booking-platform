package dev.ydt.booking.service;

import dev.ydt.booking.client.CatalogClient;
import dev.ydt.booking.client.PaymentClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Бизнес-метрики брони. В Prometheus имена получают суффикс {@code _total}: {@code bookings_placed_total} и т.д.
 * Не {@code bookings.created}: суффикс {@code _created} в OpenMetrics зарезервирован, клиент Prometheus его срезает.
 * Технические метрики (HTTP, пул Hikari, JVM, Kafka-клиенты) Spring Boot отдаёт сам.
 */
@Component
public class BookingMetrics {

    private final Counter bookingsCreated;
    private final Counter holdsExpired;
    private final Counter seatConflicts;
    private final Counter seatConflictsCaughtByDatabase;
    private final MeterRegistry registry;

    public BookingMetrics(MeterRegistry registry, CatalogClient catalogClient, PaymentClient paymentClient) {
        this.registry = registry;
        this.bookingsCreated = registry.counter("bookings.placed");
        this.holdsExpired = registry.counter("holds.expired");
        // место заняли раньше — обычный исход гонки; caught_by=database значит, что код пропустил гонку
        // и её поймал уникальный индекс. Под нагрузкой второе должно быть около нуля.
        this.seatConflicts = registry.counter("seat.conflicts", "caught_by", "application");
        this.seatConflictsCaughtByDatabase = registry.counter("seat.conflicts", "caught_by", "database");
        bind(registry, catalogClient.circuitBreaker());
        bind(registry, paymentClient.circuitBreaker());
    }

    public void bookingCreated() {
        bookingsCreated.increment();
    }

    public void holdExpired() {
        holdsExpired.increment();
    }

    public void seatConflict() {
        seatConflicts.increment();
    }

    public void seatConflictCaughtByDatabase() {
        seatConflictsCaughtByDatabase.increment();
    }

    /** @param result succeeded, declined или unavailable (таймаут, 5xx, открытый breaker) */
    public void paymentAttempt(String result) {
        registry.counter("payments", "result", result).increment();
    }

    // 0 закрыт, 1 открыт, 2 полуоткрыт (порядок состояний в Resilience4j)
    private static void bind(MeterRegistry registry, CircuitBreaker circuitBreaker) {
        Gauge.builder("circuit.breaker.state", circuitBreaker, cb -> cb.getState().getOrder())
                .tag("name", circuitBreaker.getName())
                .register(registry);
        Gauge.builder("circuit.breaker.failure.rate", circuitBreaker, cb -> cb.getMetrics().getFailureRate())
                .tag("name", circuitBreaker.getName())
                .baseUnit("percent")
                .register(registry);
    }
}
