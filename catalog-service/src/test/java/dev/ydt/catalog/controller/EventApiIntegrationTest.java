package dev.ydt.catalog.controller;

import com.jayway.jsonpath.JsonPath;
import dev.ydt.catalog.support.AbstractIntegrationTest;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EventApiIntegrationTest extends AbstractIntegrationTest {

    @Test
    void pagesFollowEachOtherWithoutGapsOrDuplicates() throws Exception {
        List<Integer> ids = new ArrayList<>();
        List<Instant> starts = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 5; page++) {
            var request = get("/api/v1/events").param("city", "City 7").param("size", "20");
            if (cursor != null) {
                request.param("after", cursor);
            }
            String body = mvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(20)))
                    .andReturn().getResponse().getContentAsString();
            ids.addAll(JsonPath.read(body, "$.items[*].id"));
            List<String> pageStarts = JsonPath.read(body, "$.items[*].startsAt");
            pageStarts.forEach(s -> starts.add(Instant.parse(s)));
            cursor = JsonPath.read(body, "$.nextCursor");
        }

        assertThat(new HashSet<>(ids)).hasSize(100);
        assertThat(starts).isSorted();
    }

    @Test
    void listOnlyShowsEventsOnSaleInTheCity() throws Exception {
        mvc.perform(get("/api/v1/events").param("city", "Almaty").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.city != 'Almaty')]").isEmpty())
                .andExpect(jsonPath("$.items[?(@.status != 'ON_SALE')]").isEmpty())
                .andExpect(jsonPath("$.items[?(@.title == 'Jazz Night')].venue").value("Almaty Arena"));
    }

    @Test
    void eventCardHasVenueAndPerformers() throws Exception {
        mvc.perform(get("/api/v1/events/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Jazz Night"))
                .andExpect(jsonPath("$.venue").value("Almaty Arena"))
                .andExpect(jsonPath("$.performers", hasSize(2)));

        mvc.perform(get("/api/v1/events/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void badRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/events/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("EventNotFound"));

        mvc.perform(get("/api/v1/events").param("after", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("InvalidCursor"));

        mvc.perform(get("/api/v1/events").param("size", "1000"))
                .andExpect(status().isBadRequest());
    }

    // N+1: без JOIN FETCH и пачечной догрузки страница из 20 событий стоила бы 1 + 20 + 20 запросов
    @Test
    void pageOfEventsTakesConstantNumberOfQueries() throws Exception {
        Statistics statistics = resetStatistics();

        mvc.perform(get("/api/v1/events").param("size", "20")).andExpect(status().isOk());

        // события с площадками одним запросом + артисты всех 20 событий одним запросом
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void eventCardTakesOneQuery() throws Exception {
        Statistics statistics = resetStatistics();

        mvc.perform(get("/api/v1/events/1")).andExpect(status().isOk());

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }
}
