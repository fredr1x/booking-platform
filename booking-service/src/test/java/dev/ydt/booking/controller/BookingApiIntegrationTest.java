package dev.ydt.booking.controller;

import com.jayway.jsonpath.JsonPath;
import dev.ydt.booking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookingApiIntegrationTest extends AbstractIntegrationTest {

    // Jazz Night: продажи открылись сутки назад, поэтому действует early bird
    private static final long EVENT_ID = 1;
    private static final long DRAFT_EVENT_ID = 3;

    @Test
    void createGetAndCancelBooking() throws Exception {
        List<Integer> seatIds = availableSeats(2);

        String body = createBooking("alice", seatIds)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("http://localhost/api/v1/bookings/")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.pricingType").value("EARLY_BIRD"))
                .andExpect(jsonPath("$.seats", hasSize(2)))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString();
        int bookingId = JsonPath.read(body, "$.id");

        mvc.perform(get("/api/v1/bookings/{id}", bookingId).header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(EVENT_ID));

        mvc.perform(post("/api/v1/bookings/{id}/cancel", bookingId).header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("USER_REQUEST"));

        // места вернулись в продажу, их можно забронировать снова
        createBooking("bob", seatIds).andExpect(status().isCreated());
    }

    @Test
    void sameSeatCannotBeBookedTwice() throws Exception {
        List<Integer> seatIds = availableSeats(1);
        createBooking("alice", seatIds).andExpect(status().isCreated());

        createBooking("bob", seatIds)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("SeatUnavailable"))
                .andExpect(jsonPath("$.seatIds[0]").value(seatIds.getFirst()));
    }

    @Test
    void cancelTwiceIsConflict() throws Exception {
        String body = createBooking("alice", availableSeats(1)).andReturn().getResponse().getContentAsString();
        int bookingId = JsonPath.read(body, "$.id");
        mvc.perform(post("/api/v1/bookings/{id}/cancel", bookingId).header("X-User-Id", "alice"));

        mvc.perform(post("/api/v1/bookings/{id}/cancel", bookingId).header("X-User-Id", "alice"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("IllegalBookingTransition"));
    }

    @Test
    void otherUserCannotSeeBooking() throws Exception {
        String body = createBooking("alice", availableSeats(1)).andReturn().getResponse().getContentAsString();
        int bookingId = JsonPath.read(body, "$.id");

        mvc.perform(get("/api/v1/bookings/{id}", bookingId).header("X-User-Id", "mallory"))
                .andExpect(status().isNotFound());
    }

    @Test
    void draftEventIsNotOnSale() throws Exception {
        mvc.perform(post("/api/v1/bookings")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\": %d, \"seatIds\": [1]}".formatted(DRAFT_EVENT_ID)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void invalidRequestIsRejected() throws Exception {
        mvc.perform(post("/api/v1/bookings")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\": 1, \"seatIds\": []}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\": 1, \"seatIds\": [1]}"))
                .andExpect(status().isBadRequest());
    }

    private List<Integer> availableSeats(int count) throws Exception {
        String body = mvc.perform(get("/api/v1/events/{id}/seats", EVENT_ID).param("limit", String.valueOf(count)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].id");
    }

    private ResultActions createBooking(String userId, List<Integer> seatIds) throws Exception {
        return mvc.perform(post("/api/v1/bookings")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\": %d, \"seatIds\": %s}".formatted(EVENT_ID, seatIds)));
    }
}
