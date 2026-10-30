package dev.ydt.payment.service;

import dev.ydt.booking.contracts.BookingEvent;
import dev.ydt.booking.contracts.BookingEvent.PaymentRequested;
import dev.ydt.booking.contracts.PaymentEvent;
import dev.ydt.booking.contracts.PaymentEvent.PaymentSucceeded;
import dev.ydt.booking.contracts.Topics;
import dev.ydt.payment.support.TopicReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PaymentFlowIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    PaymentService paymentService;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper jsonMapper;

    @Test
    void paymentRequestFromKafkaGetsResultInPaymentEvents() {
        var request = request(101L);

        kafkaTemplate.send(Topics.BOOKING_EVENTS, "101", jsonMapper.writeValueAsString(request)).join();

        try (var reader = new TopicReader(KAFKA.getBootstrapServers(), Topics.PAYMENT_EVENTS)) {
            var results = reader.await(r -> r.key().equals("101"), 1, WAIT);
            assertThat(results).hasSize(1);
            assertThat(jsonMapper.readValue(results.getFirst().value(), PaymentEvent.class))
                    .isInstanceOfSatisfying(PaymentSucceeded.class,
                            e -> assertThat(e.paymentId()).isEqualTo(request.paymentId()));
        }
    }

    @Test
    void redeliveredRequestRepublishesSameResultWithoutSecondCharge() {
        var request = request(202L);

        paymentService.process(request);
        paymentService.process(request);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE payment_id = ?", Integer.class,
                request.paymentId())).isEqualTo(1);
        try (var reader = new TopicReader(KAFKA.getBootstrapServers(), Topics.PAYMENT_EVENTS)) {
            var messageIds = reader.await(r -> r.key().equals("202"), 2, WAIT).stream()
                    .map(r -> jsonMapper.readValue(r.value(), PaymentEvent.class).messageId())
                    .toList();
            assertThat(messageIds).as("тот же messageId: booking-service отбросит повтор").hasSize(2)
                    .containsOnly(messageIds.getFirst());
        }
    }

    @Test
    void otherBookingEventsAreIgnored() {
        BookingEvent expired = new BookingEvent.BookingExpired(UUID.randomUUID(), 303L, "bob", Instant.now());
        kafkaTemplate.send(Topics.BOOKING_EVENTS, "303", jsonMapper.writeValueAsString(expired)).join();
        // запрос оплаты той же брони идёт следом в ту же партицию: когда он обработан, предыдущее тоже
        var request = request(303L);
        kafkaTemplate.send(Topics.BOOKING_EVENTS, "303", jsonMapper.writeValueAsString(request)).join();

        try (var reader = new TopicReader(KAFKA.getBootstrapServers(), Topics.PAYMENT_EVENTS)) {
            assertThat(reader.await(r -> r.key().equals("303"), 2, Duration.ofSeconds(5))).hasSize(1);
        }
    }

    private static PaymentRequested request(Long bookingId) {
        return new PaymentRequested(UUID.randomUUID(), bookingId, "alice", UUID.randomUUID().toString(),
                new BigDecimal("15000.00"), Instant.now());
    }
}
