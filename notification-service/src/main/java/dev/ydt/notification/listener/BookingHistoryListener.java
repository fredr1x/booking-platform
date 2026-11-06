package dev.ydt.notification.listener;

import dev.ydt.booking.contracts.BookingEvent;
import dev.ydt.booking.contracts.Topics;
import dev.ydt.notification.service.BookingHistoryService;
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
 * Своя consumer group, а не второй вызов в {@link BookingEventListener}: у истории свой offset, свои повторы
 * и свой лаг. Упавшая проекция не задерживает уведомления, и её можно перестроить с начала топика,
 * сбросив offset группы, не отправив уведомления второй раз.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingHistoryListener {

    private final BookingHistoryService bookingHistoryService;
    private final JsonMapper jsonMapper;

    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(delayString = "${notification.kafka.retry-delay:1s}", multiplier = 2),
            exclude = JacksonException.class,
            retryTopicSuffix = "-history-retry",
            dltTopicSuffix = "-history-dlt")
    @KafkaListener(topics = Topics.BOOKING_EVENTS, groupId = "booking-history")
    public void onBookingEvent(String payload) {
        bookingHistoryService.apply(jsonMapper.readValue(payload, BookingEvent.class));
    }

    @DltHandler
    public void onDeadLetter(String payload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("Booking event moved to {} after all retries: {}", topic, payload);
    }
}
