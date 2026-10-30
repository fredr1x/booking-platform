package dev.ydt.booking.service;

import dev.ydt.booking.contracts.BookingEvent;
import dev.ydt.booking.contracts.BookingEvent.BookingCancelled;
import dev.ydt.booking.contracts.BookingEvent.BookingConfirmed;
import dev.ydt.booking.contracts.BookingEvent.BookingCreated;
import dev.ydt.booking.contracts.BookingEvent.PaymentRequested;
import dev.ydt.booking.contracts.PaymentEvent.PaymentFailed;
import dev.ydt.booking.contracts.PaymentEvent.PaymentSucceeded;
import dev.ydt.booking.contracts.Topics;
import dev.ydt.booking.dto.booking.BookingResponse;
import dev.ydt.booking.dto.booking.CreateBookingRequest;
import dev.ydt.booking.entity.enums.BookingStatus;
import dev.ydt.booking.support.AbstractIntegrationTest;
import dev.ydt.booking.support.TopicReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Бронь → outbox → Kafka → оплата → статус брони. Роль payment-service играет тест: сам пишет
 * результат оплаты в payment.events, так сервис проверяется без второго приложения.
 */
class BookingMessagingIntegrationTest extends AbstractIntegrationTest {

    private static final String USER = "kafka-user";
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    BookingService bookingService;
    @Autowired
    OutboxService outboxService;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    JsonMapper jsonMapper;

    @BeforeEach
    void drainOutbox() {
        // другие тесты создают брони и оставляют сообщения: отправляем их, чтобы считать только свои
        while (outboxService.relayBatch() > 0) {
            Thread.onSpinWait();
        }
    }

    @Test
    void bookingIsConfirmedByPaymentResultFromKafka() throws Exception {
        BookingResponse booking = createBooking();
        assertThat(outboxSize()).as("событие лежит в outbox до отправки").isEqualTo(1);

        bookingService.pay(booking.id(), USER);
        assertThat(outboxService.relayBatch()).isEqualTo(2);
        assertThat(outboxSize()).isZero();

        try (var reader = new TopicReader(KAFKA.getBootstrapServers(), Topics.BOOKING_EVENTS)) {
            List<BookingEvent> events = eventsOf(reader, booking.id(), 2);
            assertThat(events).extracting(Object::getClass)
                    .as("события одной брони в одной партиции, порядок сохранён")
                    .containsExactly(BookingCreated.class, PaymentRequested.class);
            var request = (PaymentRequested) events.get(1);
            assertThat(request.amount()).isEqualByComparingTo(booking.totalPrice());

            sendPaymentResult(new PaymentSucceeded(UUID.randomUUID(), booking.id(), request.paymentId(), Instant.now()));

            await().atMost(WAIT).until(() -> statusOf(booking.id()) == BookingStatus.CONFIRMED);
            assertThat(seatStatuses(booking.id())).containsOnly("BOOKED");

            outboxService.relayBatch();
            assertThat(eventsOf(reader, booking.id(), 3).getLast()).isInstanceOf(BookingConfirmed.class);
        }
    }

    @Test
    void redeliveredPaymentResultChangesNothing() {
        BookingResponse booking = createBooking();
        var paying = bookingService.pay(booking.id(), USER);
        var result = new PaymentSucceeded(UUID.randomUUID(), booking.id(), paying.paymentId(), Instant.now());

        bookingService.applyPayment(result);
        int outboxAfterFirst = outboxSize();
        bookingService.applyPayment(result);

        assertThat(statusOf(booking.id())).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(outboxSize()).as("второй BookingConfirmed не появился").isEqualTo(outboxAfterFirst);
    }

    @Test
    void failedPaymentCancelsBookingAndReleasesSeats() {
        BookingResponse booking = createBooking();
        var paying = bookingService.pay(booking.id(), USER);

        bookingService.applyPayment(new PaymentFailed(UUID.randomUUID(), booking.id(), paying.paymentId(),
                "Card declined", Instant.now()));

        assertThat(statusOf(booking.id())).isEqualTo(BookingStatus.CANCELLED);
        assertThat(seatStatuses(booking.id())).containsOnly("AVAILABLE");
        String payload = jdbc.queryForObject("SELECT payload::text FROM outbox ORDER BY id DESC LIMIT 1", String.class);
        assertThat(jsonMapper.readValue(payload, BookingEvent.class))
                .isInstanceOfSatisfying(BookingCancelled.class, e -> assertThat(e.reason()).isEqualTo("PAYMENT_FAILED"));
    }

    @Test
    void brokenPaymentMessageGoesStraightToDeadLetterTopic() {
        String poison = "not-json-" + UUID.randomUUID();
        kafkaTemplate.send(Topics.PAYMENT_EVENTS, "1", poison).join();

        try (var reader = new TopicReader(KAFKA.getBootstrapServers(), Topics.PAYMENT_EVENTS + "-booking-dlt")) {
            assertThat(reader.await(r -> r.value().equals(poison), 1, WAIT)).hasSize(1);
        }
    }

    @Test
    void twoRelaysShareOutboxWithoutSendingTwice() throws Exception {
        String topic = "outbox.relay-test";
        int messages = 300;
        // сообщения в отдельный топик: их не должен читать ни один consumer сервиса
        jdbc.update("""
                INSERT INTO outbox (message_id, topic, message_key, payload, created_at)
                SELECT gen_random_uuid(), ?, g::text, json_build_object('n', g), now()
                FROM generate_series(1, ?) g
                """, topic, messages);

        int sent;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> relays = IntStream.range(0, 2)
                    .mapToObj(i -> executor.submit(this::relayAll))
                    .toList();
            sent = relays.get(0).get() + relays.get(1).get();
        }

        assertThat(sent).isEqualTo(messages);
        try (var reader = new TopicReader(KAFKA.getBootstrapServers(), topic)) {
            var received = reader.await(r -> true, messages, WAIT);
            assertThat(received).hasSize(messages);
            assertThat(new HashSet<>(received.stream().map(ConsumerRecord::value).toList())).hasSize(messages);
        }
    }

    private int relayAll() {
        int total = 0;
        int sent;
        while ((sent = outboxService.relayBatch()) > 0) {
            total += sent;
        }
        return total;
    }

    private BookingResponse createBooking() {
        Long seatId = jdbc.queryForObject(
                "SELECT id FROM seats WHERE event_id = 1 AND status = 'AVAILABLE' ORDER BY id DESC LIMIT 1", Long.class);
        return bookingService.create(USER, new CreateBookingRequest(1L, List.of(seatId)));
    }

    private void sendPaymentResult(Object result) {
        kafkaTemplate.send(Topics.PAYMENT_EVENTS, "key", jsonMapper.writeValueAsString(result)).join();
    }

    private List<BookingEvent> eventsOf(TopicReader reader, Long bookingId, int count) {
        return reader.await(r -> r.key().equals(bookingId.toString()), count, WAIT).stream()
                .map(r -> jsonMapper.readValue(r.value(), BookingEvent.class))
                .toList();
    }

    private int outboxSize() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class);
    }

    private BookingStatus statusOf(Long bookingId) {
        return BookingStatus.valueOf(jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class,
                bookingId));
    }

    private List<String> seatStatuses(Long bookingId) {
        return jdbc.queryForList("""
                SELECT s.status FROM seats s JOIN booking_seats bs ON bs.seat_id = s.id WHERE bs.booking_id = ?
                """, String.class, bookingId);
    }
}
