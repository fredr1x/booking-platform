package dev.ydt.catalog.repository;

import dev.ydt.catalog.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Страховка от регрессий: запросы ленты на 100 000 событий должны идти по частичному индексу
 * и отдавать строки уже отсортированными (в плане нет Sort). SQL тот же, что генерирует Hibernate
 * для EventRepository. Цифры до и после индексов в docs/adr/0002-catalog-queries.md.
 */
class EventQueryPlanTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void cityFeedUsesPartialIndexWithoutSort() {
        String plan = explain("""
                SELECT e.id FROM events e JOIN venues v ON v.id = e.venue_id
                WHERE e.status = 'ON_SALE' AND e.city = 'City 7' AND (e.starts_at, e.id) > (now(), 0)
                ORDER BY e.starts_at, e.id LIMIT 21
                """);

        assertThat(plan).contains("idx_events_on_sale_city").doesNotContain("Sort");
    }

    @Test
    void feedWithoutCityUsesPartialIndexWithoutSort() {
        String plan = explain("""
                SELECT e.id FROM events e JOIN venues v ON v.id = e.venue_id
                WHERE e.status = 'ON_SALE' AND (e.starts_at, e.id) > (now(), 0)
                ORDER BY e.starts_at, e.id LIMIT 21
                """);

        assertThat(plan).contains("using idx_events_on_sale on").doesNotContain("Sort");
    }

    private String explain(String sql) {
        List<String> lines = jdbc.queryForList("EXPLAIN " + sql, String.class);
        return String.join("\n", lines);
    }
}
