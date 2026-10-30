package dev.ydt.booking.contracts;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * События топика {@link Topics#BOOKING_EVENTS}. Тип пишется в JSON полем {@code type}, поэтому consumer читает
 * любое событие как {@code BookingEvent} и разбирает его switch'ем по sealed-иерархии.
 * <p>
 * {@code messageId} уникален для каждого сообщения: по нему consumer узнаёт повторную доставку.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BookingEvent.BookingCreated.class, name = "BookingCreated"),
        @JsonSubTypes.Type(value = BookingEvent.PaymentRequested.class, name = "PaymentRequested"),
        @JsonSubTypes.Type(value = BookingEvent.BookingConfirmed.class, name = "BookingConfirmed"),
        @JsonSubTypes.Type(value = BookingEvent.BookingCancelled.class, name = "BookingCancelled"),
        @JsonSubTypes.Type(value = BookingEvent.BookingExpired.class, name = "BookingExpired")
})
public sealed interface BookingEvent {

    UUID messageId();

    Long bookingId();

    String userId();

    Instant occurredAt();

    record BookingCreated(
            UUID messageId,
            Long bookingId,
            String userId,
            Long eventId,
            BigDecimal totalPrice,
            Instant expiresAt,
            Instant occurredAt
    ) implements BookingEvent {
    }

    /** Пользователь нажал «оплатить»: payment-service списывает {@code amount} и отвечает в payment.events. */
    record PaymentRequested(
            UUID messageId,
            Long bookingId,
            String userId,
            String paymentId,
            BigDecimal amount,
            Instant occurredAt
    ) implements BookingEvent {
    }

    record BookingConfirmed(
            UUID messageId,
            Long bookingId,
            String userId,
            String paymentId,
            Instant occurredAt
    ) implements BookingEvent {
    }

    /** {@code reason}: USER_REQUEST или PAYMENT_FAILED. Строка, а не enum: новая причина не ломает старых consumer'ов. */
    record BookingCancelled(
            UUID messageId,
            Long bookingId,
            String userId,
            String reason,
            Instant occurredAt
    ) implements BookingEvent {
    }

    record BookingExpired(
            UUID messageId,
            Long bookingId,
            String userId,
            Instant occurredAt
    ) implements BookingEvent {
    }
}
