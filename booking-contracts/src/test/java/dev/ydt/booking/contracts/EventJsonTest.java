package dev.ydt.booking.contracts;

import dev.ydt.booking.contracts.BookingEvent.BookingCancelled;
import dev.ydt.booking.contracts.PaymentEvent.PaymentFailed;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void bookingEventKeepsItsTypeThroughJson() {
        BookingEvent event = new BookingCancelled(UUID.randomUUID(), 7L, "alice", "PAYMENT_FAILED",
                Instant.parse("2026-10-30T10:00:00Z"));

        var json = mapper.writeValueAsString(event);

        assertThat(json).contains("\"type\":\"BookingCancelled\"");
        assertThat(mapper.readValue(json, BookingEvent.class)).isEqualTo(event);
    }

    @Test
    void paymentEventKeepsItsTypeThroughJson() {
        PaymentEvent event = new PaymentFailed(UUID.randomUUID(), 7L, "pay-1", "Card declined",
                Instant.parse("2026-10-30T10:00:00Z"));

        assertThat(mapper.readValue(mapper.writeValueAsString(event), PaymentEvent.class)).isEqualTo(event);
    }
}
