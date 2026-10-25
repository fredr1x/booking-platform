package dev.ydt.booking.controller;

import com.jayway.jsonpath.JsonPath;
import dev.ydt.booking.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookingApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    EntityManagerFactory entityManagerFactory;

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
    void unknownEventIsNotFound() throws Exception {
        mvc.perform(post("/api/v1/bookings")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\": 999, \"seatIds\": [1]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("EventNotFound"));
    }

    // каталог лежит: это не «события нет» (404), а временная ошибка, которую можно повторить
    @Test
    void catalogFailureIsServiceUnavailable() throws Exception {
        mvc.perform(post("/api/v1/bookings")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\": %d, \"seatIds\": [1]}".formatted(BROKEN_CATALOG_EVENT_ID)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("CatalogUnavailable"));
    }

    // P4: без default_batch_fetch_size каждое место брони догружалось отдельным запросом
    @Test
    void bookingWithManySeatsLoadsInConstantQueries() throws Exception {
        String body = createBooking("alice", availableSeats(5)).andReturn().getResponse().getContentAsString();
        int bookingId = JsonPath.read(body, "$.id");
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mvc.perform(get("/api/v1/bookings/{id}", bookingId).header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats", hasSize(5)));

        // бронь + её строки booking_seats + все 5 мест одним IN-запросом
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void invalidRequestIsRejected() throws Exception {
        mvc.perform(post("/api/v1/bookings")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\": 1, \"seatIds\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.title").value("ValidationFailed"))
                .andExpect(jsonPath("$.errors[0].field").value("seatIds"))
                .andExpect(jsonPath("$.instance").value("/api/v1/bookings"));

        mvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\": 1, \"seatIds\": [1]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Required header 'X-User-Id' is not present."));

        mvc.perform(post("/api/v1/bookings")
                        .header("X-User-Id", "u".repeat(65))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\": 1, \"seatIds\": [1]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("X-User-Id must be at most 64 characters"));

        mvc.perform(post("/api/v1/bookings")
                        .header("X-User-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\": 1, \"seatIds\": ["))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"));
    }

    @Test
    void retryWithSameIdempotencyKeyReturnsSameBooking() throws Exception {
        List<Integer> seatIds = availableSeats(1);

        String first = createBooking("alice", "key-1", seatIds)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retry = createBooking("alice", "key-1", seatIds)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat((int) JsonPath.read(retry, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
    }

    @Test
    void sameKeyFromAnotherUserIsIndependent() throws Exception {
        createBooking("alice", "shared-key", availableSeats(1)).andExpect(status().isCreated());

        createBooking("bob", "shared-key", availableSeats(1)).andExpect(status().isCreated());
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() throws Exception {
        createBooking("alice", "key-2", availableSeats(1)).andExpect(status().isCreated());

        createBooking("alice", "key-2", availableSeats(2))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("IdempotencyKeyReused"));
    }

    @Test
    void failedRequestDoesNotBurnTheKey() throws Exception {
        List<Integer> taken = availableSeats(1);
        createBooking("bob", taken).andExpect(status().isCreated());
        createBooking("alice", "key-3", taken).andExpect(status().isConflict());

        // ключ откатился вместе с неудачной бронью: тот же запрос с тем же ключом выполняется заново
        createBooking("alice", "key-3", taken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("SeatUnavailable"));
    }

    @Test
    void tooLongIdempotencyKeyIsRejected() throws Exception {
        createBooking("alice", "k".repeat(256), availableSeats(1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("ValidationFailed"))
                .andExpect(jsonPath("$.errors[0].field").value("idempotencyKey"));
    }

    @Test
    void openApiDescribesUserHeader() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/bookings'].post.parameters[?(@.name == 'X-User-Id')].in")
                        .value("header"))
                .andExpect(jsonPath("$.paths['/api/v1/bookings'].post.parameters[?(@.name == 'userId')]")
                        .isEmpty());
    }

    private List<Integer> availableSeats(int count) throws Exception {
        String body = mvc.perform(get("/api/v1/events/{id}/seats", EVENT_ID).param("limit", String.valueOf(count)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].id");
    }

    private ResultActions createBooking(String userId, List<Integer> seatIds) throws Exception {
        return createBooking(userId, null, seatIds);
    }

    private ResultActions createBooking(String userId, String idempotencyKey, List<Integer> seatIds) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/bookings")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\": %d, \"seatIds\": %s}".formatted(EVENT_ID, seatIds));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }
}
