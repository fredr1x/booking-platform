package dev.ydt.booking.controller;

import dev.ydt.booking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EventApiIntegrationTest extends AbstractIntegrationTest {

    @Test
    void migrationsAndSeedAreApplied() throws Exception {
        mvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }

    // Брони в других тестах делаются только на событие 1, событие 2 остаётся нетронутым.
    @Test
    void flashSaleEventHasTwentyThousandAvailableSeats() throws Exception {
        mvc.perform(get("/api/v1/events/2/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats.AVAILABLE").value(20_000))
                .andExpect(jsonPath("$.seats.BOOKED").value(0));
    }

    @Test
    void seatsAreLimited() throws Exception {
        mvc.perform(get("/api/v1/events/1/seats").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(10)));
    }

    @Test
    void unknownEventReturns404() throws Exception {
        mvc.perform(get("/api/v1/events/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("EventNotFound"));
    }
}
