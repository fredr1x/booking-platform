package dev.ydt.catalog.repository;

import dev.ydt.catalog.entity.EventDocument;
import dev.ydt.catalog.entity.enums.EventStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** Индекс событий в Elasticsearch. */
@Repository
@RequiredArgsConstructor
public class EventSearchRepository {

    private final ElasticsearchOperations operations;

    public void createIndexIfMissing() {
        var index = operations.indexOps(EventDocument.class);
        if (!index.exists()) {
            index.createWithMapping();
        }
    }

    public long count() {
        return operations.count(Query.findAll(), EventDocument.class);
    }

    /** Одним bulk-запросом. Документ с тем же id перезаписывается, поэтому повторная индексация безопасна. */
    public void saveAll(List<EventDocument> documents) {
        operations.save(documents);
    }

    /** Новые документы видны поиску после refresh (по умолчанию раз в секунду), тест не ждёт. */
    public void refresh() {
        operations.indexOps(EventDocument.class).refresh();
    }

    /**
     * Полнотекстовый поиск с опечатками по названию, артистам и площадке (название весит больше всего)
     * и фильтры. Фильтры в {@code filter}, а не в {@code must}: они не влияют на релевантность
     * и кэшируются Elasticsearch'ем.
     * <p>
     * {@code fuzziness: AUTO} — допустимое число опечаток от длины слова: до 2 символов ни одной,
     * 3–5 символов одна, длиннее две. Перестановка соседних букв считается одной опечаткой.
     */
    public SearchHits<EventDocument> search(String text, String city, Instant from, Instant to, Pageable pageable) {
        var query = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> {
                    b.must(m -> m.multiMatch(mm -> mm
                            .query(text)
                            .fields("title^3", "performers^2", "venue")
                            .fuzziness("AUTO")));
                    b.filter(f -> f.term(t -> t.field("status").value(EventStatus.ON_SALE.name())));
                    if (city != null) {
                        b.filter(f -> f.term(t -> t.field("city").value(city)));
                    }
                    if (from != null || to != null) {
                        b.filter(f -> f.range(r -> r.date(d -> {
                            d.field("startsAt");
                            if (from != null) {
                                d.gte(from.toString());
                            }
                            if (to != null) {
                                d.lt(to.toString());
                            }
                            return d;
                        })));
                    }
                    return b;
                }))
                .withPageable(pageable)
                .build();
        return operations.search(query, EventDocument.class);
    }
}
