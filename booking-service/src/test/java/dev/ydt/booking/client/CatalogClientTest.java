package dev.ydt.booking.client;

import dev.ydt.booking.dto.event.CatalogEvent;
import dev.ydt.booking.entity.enums.EventStatus;
import dev.ydt.booking.exception.CatalogUnavailableException;
import dev.ydt.booking.exception.EventNotFoundException;
import dev.ydt.booking.support.AbstractIntegrationTest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Свой клиент на каждый тест: у бина из контекста кэш и circuit breaker общие с остальными тестами.
class CatalogClientTest extends AbstractIntegrationTest {

    // на этом порту никто не слушает: каталог «лежит»
    private static final String DEAD_CATALOG_URL = "http://localhost:1";

    @Autowired
    StringRedisTemplate redis;
    @Autowired
    JsonMapper jsonMapper;

    private CatalogClient client;

    @BeforeEach
    void newClient() {
        client = client(catalogUrl());
    }

    @Test
    void concurrentLookupsOfOneEventMakeOneRequest() throws Exception {
        int before = CATALOG_REQUESTS.get();
        var start = new CountDownLatch(1);

        List<CatalogEvent> events;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<CatalogEvent>> results = IntStream.range(0, 200)
                    .mapToObj(i -> executor.submit(() -> {
                        start.await();
                        return client.getEvent(2L);
                    }))
                    .toList();
            start.countDown();
            events = results.stream().map(CatalogClientTest::get).toList();
        }

        assertThat(events).extracting(CatalogEvent::status).containsOnly(EventStatus.ON_SALE);
        assertThat(CATALOG_REQUESTS.get() - before).isEqualTo(1);
    }

    @Test
    void errorsAreNotCached() {
        int before = CATALOG_REQUESTS.get();

        assertThatThrownBy(() -> client.getEvent(BROKEN_CATALOG_EVENT_ID)).isInstanceOf(CatalogUnavailableException.class);
        assertThatThrownBy(() -> client.getEvent(BROKEN_CATALOG_EVENT_ID)).isInstanceOf(CatalogUnavailableException.class);
        assertThatThrownBy(() -> client.getEvent(404L)).isInstanceOf(EventNotFoundException.class);

        assertThat(CATALOG_REQUESTS.get() - before).isEqualTo(3);
    }

    @Test
    void downCatalogIsReplacedByLastKnownEventFromRedis() {
        CatalogEvent known = client.getEvent(1L);
        var down = client(DEAD_CATALOG_URL);

        assertThat(down.getEvent(1L)).as("последнее известное событие из Redis").isEqualTo(known);
        redis.delete("catalog-event:3");
        assertThatThrownBy(() -> down.getEvent(3L))
                .as("этого события в Redis нет, отвечать нечем")
                .isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void circuitBreakerOpensAfterRepeatedFailuresAndStopsCallingCatalog() {
        var down = client(DEAD_CATALOG_URL);
        redis.delete("catalog-event:3");

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> down.getEvent(3L)).isInstanceOf(CatalogUnavailableException.class);
        }

        assertThat(down.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> down.getEvent(3L))
                .hasRootCauseInstanceOf(CallNotPermittedException.class);
    }

    private CatalogClient client(String url) {
        return new CatalogClient(url, Duration.ofMillis(500), Duration.ofMinutes(1), Duration.ofMinutes(10), redis,
                jsonMapper);
    }

    private static CatalogEvent get(Future<CatalogEvent> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
