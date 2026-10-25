package dev.ydt.booking.controller;

import dev.ydt.booking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Список и карточки событий теперь в catalog-service, здесь только инвентарь мест.
class EventApiIntegrationTest extends AbstractIntegrationTest {

    // Событие 3 в статусе DRAFT, его никто не бронирует, поэтому все 200 мест свободны.
    @Test
    void draftEventHasAllSeatsAvailable() throws Exception {
        mvc.perform(get("/api/v1/events/3/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats.AVAILABLE").value(200))
                .andExpect(jsonPath("$.seats.BOOKED").value(0));
    }

    @Test
    void seatsAreLimited() throws Exception {
        mvc.perform(get("/api/v1/events/1/seats").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(10)));
    }

    @Test
    void eventWithoutSeatsReturns404() throws Exception {
        mvc.perform(get("/api/v1/events/999/availability"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("EventNotFound"));
    }
}
