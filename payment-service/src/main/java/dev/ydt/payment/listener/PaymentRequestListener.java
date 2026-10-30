package dev.ydt.payment.listener;

import dev.ydt.booking.contracts.BookingEvent;
import dev.ydt.booking.contracts.BookingEvent.PaymentRequested;
import dev.ydt.booking.contracts.Topics;
import dev.ydt.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Из booking.events нужны только запросы оплаты, остальные события брони платёжке не интересны. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRequestListener {

    private final PaymentService paymentService;
    private final JsonMapper jsonMapper;

    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(delayString = "${payment.kafka.retry-delay:1s}", multiplier = 2),
            exclude = JacksonException.class,
            retryTopicSuffix = "-payment-retry",
            dltTopicSuffix = "-payment-dlt")
    @KafkaListener(topics = Topics.BOOKING_EVENTS, groupId = "payment-service")
    public void onBookingEvent(String payload) {
        if (jsonMapper.readValue(payload, BookingEvent.class) instanceof PaymentRequested request) {
            paymentService.process(request);
        }
    }

    @DltHandler
    public void onDeadLetter(String payload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("Booking event moved to {} after all retries: {}", topic, payload);
    }
}
