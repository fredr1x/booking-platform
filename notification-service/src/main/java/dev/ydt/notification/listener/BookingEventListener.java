package dev.ydt.notification.listener;

import dev.ydt.booking.contracts.BookingEvent;
import dev.ydt.booking.contracts.Topics;
import dev.ydt.notification.service.NotificationService;
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
 * Ошибка (например, MongoDB недоступна) не останавливает партицию: сообщение уходит в retry-топик
 * с растущей паузой, после последней попытки в DLT. Битый JSON повторять бессмысленно, он сразу в DLT.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventListener {

    private final NotificationService notificationService;
    private final JsonMapper jsonMapper;

    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(delayString = "${notification.kafka.retry-delay:1s}", multiplier = 2),
            exclude = JacksonException.class,
            retryTopicSuffix = "-notification-retry",
            dltTopicSuffix = "-notification-dlt")
    @KafkaListener(topics = Topics.BOOKING_EVENTS, groupId = "notification-service")
    public void onBookingEvent(String payload) {
        notificationService.handle(jsonMapper.readValue(payload, BookingEvent.class));
    }

    @DltHandler
    public void onDeadLetter(String payload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("Booking event moved to {} after all retries: {}", topic, payload);
    }
}
