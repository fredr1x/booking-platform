package dev.ydt.catalog.repository;

import dev.ydt.catalog.entity.Event;
import dev.ydt.catalog.support.AbstractIntegrationTest;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Показывает проблему, которую решают JOIN FETCH и default_batch_fetch_size: обычный findAll и
 * ленивые связи. Пачечная загрузка здесь выключена (размер 1), поэтому у теста свой Spring-контекст.
 */
@TestPropertySource(properties = "spring.jpa.properties.hibernate.default_batch_fetch_size=1")
class NPlusOneDemoTest extends AbstractIntegrationTest {

    @Autowired
    EventRepository eventRepository;
    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    void naivePageTriggersQueryPerEvent() {
        Statistics statistics = resetStatistics();

        transactionTemplate.executeWithoutResult(status -> {
            List<Event> events = eventRepository.findAll(PageRequest.of(0, 20)).getContent();
            // ровно то, что делает маппер в DTO: обращение к ленивым связям
            events.forEach(e -> {
                e.getVenue().getName();
                e.getPerformers().size();
            });
        });

        // 1 запрос событий + 1 count для Page + по запросу на площадку и на артистов каждого события
        assertThat(statistics.getPrepareStatementCount()).isGreaterThan(30);
    }
}
