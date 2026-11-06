package dev.ydt.catalog.service;

import dev.ydt.catalog.dto.event.EventSearchPage;
import dev.ydt.catalog.entity.EventDocument;
import dev.ydt.catalog.mapper.EventMapper;
import dev.ydt.catalog.repository.EventRepository;
import dev.ydt.catalog.repository.EventSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventSearchService {

    private static final int BATCH_SIZE = 1000;

    private final EventRepository eventRepository;
    private final EventSearchRepository eventSearchRepository;
    private final EventMapper eventMapper;
    private final TransactionTemplate transactionTemplate;

    public EventSearchPage search(String text, String city, Instant from, Instant to, int page, int size) {
        var hits = eventSearchRepository.search(text, city, from, to, PageRequest.of(page, size));
        List<EventDocument> documents = hits.getSearchHits().stream().map(SearchHit::getContent).toList();
        return new EventSearchPage(eventMapper.toResponsesFromDocuments(documents), hits.getTotalHits());
    }

    /**
     * На старте: в индексе меньше событий, чем в БД (индекс пуст или прошлая индексация прервалась), —
     * переиндексировать. Elasticsearch недоступен — каталог всё равно стартует, не работает только поиск.
     */
    // shortcut: индекс обновляется полной переиндексацией; появится API изменения событий — outbox catalog.events в Kafka и consumer, обновляющий документ
    @EventListener(ApplicationReadyEvent.class)
    public void reindexIfStale() {
        try {
            eventSearchRepository.createIndexIfMissing();
            if (eventSearchRepository.count() < eventRepository.count()) {
                reindex();
            }
        } catch (RuntimeException e) {
            log.warn("Search index is not ready, search will fail until Elasticsearch is back: {}", e.getMessage());
        }
    }

    /**
     * Все события пачками по id: keyset, а не OFFSET, поэтому каждая пачка дешёвая. Пачка читается в своей
     * транзакции (артисты догружаются лениво) и уходит в Elasticsearch одним bulk-запросом.
     *
     * @return сколько событий проиндексировано
     */
    public int reindex() {
        long started = System.nanoTime();
        long lastId = 0;
        int indexed = 0;
        while (true) {
            long after = lastId;
            List<EventDocument> batch = Objects.requireNonNull(transactionTemplate.execute(status ->
                    eventMapper.toDocuments(eventRepository.findByIdGreaterThanOrderById(after, Limit.of(BATCH_SIZE)))));
            if (batch.isEmpty()) {
                break;
            }
            eventSearchRepository.saveAll(batch);
            indexed += batch.size();
            lastId = batch.getLast().getId();
        }
        eventSearchRepository.refresh();
        log.info("Indexed {} events in {} ms", indexed, (System.nanoTime() - started) / 1_000_000);
        return indexed;
    }
}
