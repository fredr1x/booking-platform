package dev.ydt.catalog.controller;

import dev.ydt.catalog.repository.EventSearchRepository;
import dev.ydt.catalog.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Поиск по индексу, который каталог построил на старте из 100 000 демо-событий. */
class EventSearchIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    EventSearchRepository eventSearchRepository;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void allEventsAreIndexedOnStartup() {
        assertThat(eventSearchRepository.count())
                .isEqualTo(jdbc.queryForObject("SELECT count(*) FROM events", Long.class));
    }

    @Test
    void findsEventDespiteTypos() throws Exception {
        mvc.perform(get("/api/v1/events/search").param("q", "jaz nigth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Jazz Night"))
                .andExpect(jsonPath("$.items[0].venue").value("Almaty Arena"));
    }

    @Test
    void filtersByCityAndDate() throws Exception {
        mvc.perform(get("/api/v1/events/search")
                        .param("q", "evnt")
                        .param("city", "City 5")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", greaterThan(0)))
                .andExpect(jsonPath("$.items[*].city", everyItem(is("City 5"))))
                .andExpect(jsonPath("$.items[*].status", everyItem(is("ON_SALE"))));
    }

    @Test
    void draftEventsAreNotFound() throws Exception {
        mvc.perform(get("/api/v1/events/search").param("q", "Stand-up Evening"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void emptyQueryIsRejected() throws Exception {
        mvc.perform(get("/api/v1/events/search").param("q", " "))
                .andExpect(status().isBadRequest());
    }
}
