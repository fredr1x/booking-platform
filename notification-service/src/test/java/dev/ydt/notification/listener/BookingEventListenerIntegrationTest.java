package dev.ydt.notification.listener;

import dev.ydt.booking.contracts.BookingEvent;
import dev.ydt.booking.contracts.BookingEvent.BookingConfirmed;
import dev.ydt.booking.contracts.BookingEvent.BookingCreated;
import dev.ydt.booking.contracts.BookingEvent.BookingExpired;
import dev.ydt.booking.contracts.BookingEvent.PaymentRequested;
import dev.ydt.booking.contracts.Topics;
import dev.ydt.notification.entity.Notification;
import dev.ydt.notification.entity.enums.BookingStatus;
import dev.ydt.notification.repository.NotificationRepository;
import dev.ydt.notification.service.BookingHistoryService;
import dev.ydt.notification.support.TopicReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
class BookingEventListenerIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");

    static {
        MONGO.start();
        KAFKA.start();
    }

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    NotificationRepository notificationRepository;
    @Autowired
    JsonMapper jsonMapper;
    @Autowired
    BookingHistoryService bookingHistoryService;

    @Test
    void redeliveredEventNotifiesOnce() {
        long bookingId = 501;
        var confirmed = new BookingConfirmed(UUID.randomUUID(), bookingId, "alice", "pay-1", Instant.now());

        send(confirmed);
        send(confirmed);
        send(new PaymentRequested(UUID.randomUUID(), bookingId, "alice", "pay-1", BigDecimal.TEN, Instant.now()));
        // последнее событие той же брони в той же партиции: когда оно обработано, предыдущие тоже
        send(new BookingExpired(UUID.randomUUID(), bookingId, "alice", Instant.now()));

        await().atMost(WAIT).until(() -> notificationRepository.findByBookingIdOrderByCreatedAt(bookingId).stream()
                .anyMatch(n -> n.getType().equals("BookingExpired")));
        assertThat(notificationRepository.findByBookingIdOrderByCreatedAt(bookingId))
                .as("дубль BookingConfirmed отброшен, PaymentRequested пользователю не пишется")
                .extracting(Notification::getType)
                .containsExactly("BookingConfirmed", "BookingExpired");
    }

    @Test
    void historyKeepsNewestStatusWhenEventsArriveOutOfOrder() {
        long bookingId = 502;
        var createdAt = Instant.parse("2026-11-06T10:00:00Z");
        var confirmedAt = createdAt.plusSeconds(60);
        var created = new BookingCreated(UUID.randomUUID(), bookingId, "bob", 1L, new BigDecimal("15000.00"),
                createdAt.plusSeconds(600), createdAt);

        // подтверждение обогнало создание (как после retry-топика), потом дубль создания
        send(new BookingConfirmed(UUID.randomUUID(), bookingId, "bob", "pay-2", confirmedAt));
        send(created);
        send(created);

        await().atMost(WAIT).until(() -> bookingHistoryService.findByUser("bob", 0, 20).stream()
                .anyMatch(b -> b.id() == bookingId && b.eventId() != null));
        assertThat(bookingHistoryService.findByUser("bob", 0, 20))
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.status()).as("старое событие статус не откатило").isEqualTo(BookingStatus.CONFIRMED);
                    assertThat(b.totalPrice()).isEqualByComparingTo("15000");
                    assertThat(b.paymentId()).isEqualTo("pay-2");
                    assertThat(b.createdAt()).isEqualTo(createdAt);
                    assertThat(b.updatedAt()).isEqualTo(confirmedAt);
                });
        assertThat(bookingHistoryService.findByUser("alice", 0, 20)).noneMatch(b -> b.id() == bookingId);
    }

    @Test
    void brokenMessageGoesStraightToDeadLetterTopic() {
        String poison = "{\"type\":\"Unknown\"," + UUID.randomUUID() + "}";
        kafkaTemplate.send(Topics.BOOKING_EVENTS, "1", poison).join();

        try (var reader = new TopicReader(KAFKA.getBootstrapServers(), Topics.BOOKING_EVENTS + "-notification-dlt")) {
            assertThat(reader.await(r -> r.value().equals(poison), 1, WAIT)).hasSize(1);
        }
    }

    private void send(BookingEvent event) {
        kafkaTemplate.send(Topics.BOOKING_EVENTS, event.bookingId().toString(), jsonMapper.writeValueAsString(event))
                .join();
    }
}
