package dev.ydt.booking.contracts;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.UUID;

/** События топика {@link Topics#PAYMENT_EVENTS}: результат оплаты по {@code paymentId} брони. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PaymentEvent.PaymentSucceeded.class, name = "PaymentSucceeded"),
        @JsonSubTypes.Type(value = PaymentEvent.PaymentFailed.class, name = "PaymentFailed")
})
public sealed interface PaymentEvent {

    UUID messageId();

    Long bookingId();

    String paymentId();

    Instant occurredAt();

    record PaymentSucceeded(
            UUID messageId,
            Long bookingId,
            String paymentId,
            Instant occurredAt
    ) implements PaymentEvent {
    }

    record PaymentFailed(
            UUID messageId,
            Long bookingId,
            String paymentId,
            String reason,
            Instant occurredAt
    ) implements PaymentEvent {
    }
}
