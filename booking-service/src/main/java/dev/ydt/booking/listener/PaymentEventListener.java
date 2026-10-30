package dev.ydt.booking.listener;

import dev.ydt.booking.contracts.PaymentEvent;
import dev.ydt.booking.contracts.Topics;
import dev.ydt.booking.service.BookingService;
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

/**
 * Результаты оплаты из payment-service. Ошибка обработки не блокирует партицию: сообщение уходит
 * в retry-топик с растущей паузой, после последней попытки в DLT. Битый JSON повторять бессмысленно,
 * он сразу идёт в DLT.
 * <p>
 * Суффиксы retry- и DLT-топиков свои у каждой группы consumer'ов: общий {@code payment.events-retry}
 * читали бы все группы, и повтор, нужный одному сервису, получили бы все остальные.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final BookingService bookingService;
    private final JsonMapper jsonMapper;

    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(delayString = "${booking.kafka.retry-delay:1s}", multiplier = 2),
            exclude = JacksonException.class,
            retryTopicSuffix = "-booking-retry",
            dltTopicSuffix = "-booking-dlt")
    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "booking-service")
    public void onPaymentEvent(String payload) {
        bookingService.applyPayment(jsonMapper.readValue(payload, PaymentEvent.class));
    }

    @DltHandler
    public void onDeadLetter(String payload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("Payment event moved to {} after all retries: {}", topic, payload);
    }
}
