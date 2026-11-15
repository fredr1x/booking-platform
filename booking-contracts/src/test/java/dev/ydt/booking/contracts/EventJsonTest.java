package dev.ydt.booking.contracts;

import dev.ydt.booking.contracts.BookingEvent.BookingCancelled;
import dev.ydt.booking.contracts.payment.PaymentResult;
import dev.ydt.booking.contracts.payment.PaymentStatus;
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
    void paymentResultKeepsOnlyItsFieldsInJson() {
        var result = new PaymentResult("pay-1", PaymentStatus.FAILED, "Card declined");

        var json = mapper.writeValueAsString(result);

        assertThat(json).doesNotContain("succeeded");
        assertThat(mapper.readValue(json, PaymentResult.class)).isEqualTo(result);
    }
}
