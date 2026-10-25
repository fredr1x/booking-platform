package dev.ydt.booking.client;

import dev.ydt.booking.dto.event.CatalogEvent;
import dev.ydt.booking.exception.CatalogUnavailableException;
import dev.ydt.booking.exception.EventNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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
 */
// shortcut: кэш свой у каждого инстанса и без circuit breaker; общий кэш в Redis в блоке 7а, Resilience4j в блоке 9
@Component
public class CatalogClient {

    private record Entry(CompletableFuture<CatalogEvent> event, Instant expiresAt) {
    }

    private final RestClient restClient;
    private final Duration cacheTtl;
    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();

    public CatalogClient(@Value("${catalog.url}") String url,
                         @Value("${catalog.timeout:2s}") Duration timeout,
                         @Value("${catalog.cache-ttl:5s}") Duration cacheTtl) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().baseUrl(url).requestFactory(requestFactory).build();
        this.cacheTtl = cacheTtl;
    }

    public CatalogEvent getEvent(Long eventId) {
        Instant now = Instant.now();
        CompletableFuture<CatalogEvent> mine = new CompletableFuture<>();
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
        try {
            return restClient.get()
                    .uri("/api/v1/events/{id}", eventId)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (request, response) -> {
                        throw new EventNotFoundException(eventId);
                    })
                    .body(CatalogEvent.class);
        } catch (RestClientException e) {
            // таймаут, отказ соединения, 5xx: каталог недоступен, а не «события нет»
            throw new CatalogUnavailableException(eventId, e);
        }
    }
}
