package dev.ydt.booking.client;

import dev.ydt.booking.contracts.web.RequestIdFilter;
import dev.ydt.booking.dto.event.CatalogEvent;
import dev.ydt.booking.exception.CatalogUnavailableException;
import dev.ydt.booking.exception.EventNotFoundException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Синхронный вызов catalog-service: бронь проверяет, что событие существует и в продаже.
 * Таймаут обязателен: без него зависший каталог держал бы поток брони сколько угодно.
 * <p>
 * На старте продаж тысяча броней за секунду спрашивает каталог об одном и том же событии.
 * Поэтому одновременные запросы про одно событие склеиваются в один HTTP-вызов (single-flight),
 * а ответ живёт в памяти {@code catalog.cache-ttl}. Ошибки не кэшируются.
 * <p>
 * Каталог лежит или circuit breaker открыт — отвечаем последним известным событием из Redis
 * ({@code catalog.fallback-ttl}). Бронь продолжает работать, хотя каталог недоступен.
 */
// shortcut: в fallback статус события может отстать на fallback-ttl (продажу закрыли, а бронь ещё проходит);
// при API изменения каталога слать событие закрытия продаж и чистить запись
@Slf4j
@Component
public class CatalogClient {

    private static final String FALLBACK_KEY = "catalog-event:";

    private record Entry(CompletableFuture<CatalogEvent> event, Instant expiresAt) {
    }

    private final RestClient restClient;
    private final Duration cacheTtl;
    private final Duration fallbackTtl;
    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final CircuitBreaker circuitBreaker;
    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();

    public CatalogClient(@Value("${catalog.url}") String url,
                         @Value("${catalog.timeout:2s}") Duration timeout,
                         @Value("${catalog.cache-ttl:5s}") Duration cacheTtl,
                         @Value("${catalog.fallback-ttl:10m}") Duration fallbackTtl,
                         StringRedisTemplate redis,
                         JsonMapper jsonMapper) {
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().baseUrl(url).requestFactory(requestFactory)
                .requestInterceptor(RequestIdFilter.propagate()).build();
        this.cacheTtl = cacheTtl;
        this.fallbackTtl = fallbackTtl;
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.circuitBreaker = CircuitBreaker.of("catalog", CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                // «события нет» — правильный ответ каталога, а не сбой
                .ignoreExceptions(EventNotFoundException.class)
                .build());
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("Catalog circuit breaker: {}", event.getStateTransition()));
    }

    public CatalogEvent getEvent(Long eventId) {
        try {
            return getCoalesced(eventId);
        } catch (CatalogUnavailableException e) {
            CatalogEvent fallback = readFallback(eventId);
            if (fallback == null) {
                throw e;
            }
            log.warn("Catalog is unavailable, using last known event {}", eventId);
            return fallback;
        }
    }

    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    private CatalogEvent getCoalesced(Long eventId) {
        var now = Instant.now();
        var mine = new CompletableFuture<CatalogEvent>();
        // внутри compute только замена записи, без HTTP: блокировка ячейки карты держится микросекунды
        Entry entry = cache.compute(eventId, (id, old) ->
                old != null && old.expiresAt().isAfter(now) ? old : new Entry(mine, now.plus(cacheTtl)));
        if (entry.event() == mine) {
            try {
                mine.complete(fetch(eventId));
            } catch (RuntimeException e) {
                cache.remove(eventId, entry);
                mine.completeExceptionally(e);
            }
        }
        try {
            return entry.event().join();
        } catch (CompletionException e) {
            throw (RuntimeException) e.getCause();
        }
    }

    private CatalogEvent fetch(Long eventId) {
        CatalogEvent event;
        try {
            event = circuitBreaker.executeSupplier(() -> restClient.get()
                    .uri("/api/v1/events/{id}", eventId)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (request, response) -> {
                        throw new EventNotFoundException(eventId);
                    })
                    .body(CatalogEvent.class));
        } catch (RestClientException | CallNotPermittedException e) {
            // таймаут, отказ соединения, 5xx, открытый breaker: каталог недоступен, а не «события нет»
            throw new CatalogUnavailableException(eventId, e);
        }
        writeFallback(event);
        return event;
    }

    // Redis здесь только запасной путь: его сбой не должен ломать бронь, поэтому ошибки глушим
    private void writeFallback(CatalogEvent event) {
        try {
            redis.opsForValue().set(FALLBACK_KEY + event.id(), jsonMapper.writeValueAsString(event), fallbackTtl);
        } catch (DataAccessException e) {
            log.debug("Cannot save catalog fallback for event {}", event.id(), e);
        }
    }

    private CatalogEvent readFallback(Long eventId) {
        try {
            String json = redis.opsForValue().get(FALLBACK_KEY + eventId);
            return json == null ? null : jsonMapper.readValue(json, CatalogEvent.class);
        } catch (DataAccessException e) {
            return null;
        }
    }
}
