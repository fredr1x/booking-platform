package dev.ydt.booking.client;

import dev.ydt.booking.dto.event.CatalogEvent;
import dev.ydt.booking.entity.enums.EventStatus;
import dev.ydt.booking.exception.CatalogUnavailableException;
import dev.ydt.booking.exception.EventNotFoundException;
import dev.ydt.booking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Свой клиент на каждый тест: у бина из контекста кэш общий с остальными тестами.
class CatalogClientTest extends AbstractIntegrationTest {

    private final CatalogClient client = new CatalogClient(catalogUrl(), Duration.ofSeconds(2), Duration.ofMinutes(1));

    @Test
    void concurrentLookupsOfOneEventMakeOneRequest() throws Exception {
        int before = CATALOG_REQUESTS.get();
        CountDownLatch start = new CountDownLatch(1);

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

    private static CatalogEvent get(Future<CatalogEvent> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
