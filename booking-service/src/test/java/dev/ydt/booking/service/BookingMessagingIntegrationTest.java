package dev.ydt.booking.service;

import dev.ydt.booking.contracts.BookingEvent;
import dev.ydt.booking.contracts.BookingEvent.BookingConfirmed;
import dev.ydt.booking.contracts.BookingEvent.BookingCreated;
import dev.ydt.booking.contracts.BookingEvent.PaymentRequested;
import dev.ydt.booking.contracts.Topics;
import dev.ydt.booking.dto.booking.BookingResponse;
import dev.ydt.booking.dto.booking.CreateBookingRequest;
import dev.ydt.booking.support.AbstractIntegrationTest;
import dev.ydt.booking.support.TopicReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Бронь → outbox → Kafka: порядок событий одной брони и два релея на одном outbox. */
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
    PaymentSagaService sagaService;
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
    void bookingEventsGoToKafkaInOrder() {
        BookingResponse booking = createBooking();
        assertThat(outboxSize()).as("событие лежит в outbox до отправки").isEqualTo(1);

        // заглушка платёжки подтверждает сразу: Created, PaymentRequested и Confirmed уже в outbox
        sagaService.pay(booking.id(), USER);
        assertThat(outboxService.relayBatch()).isEqualTo(3);
        assertThat(outboxSize()).isZero();

        try (var reader = new TopicReader(KAFKA.getBootstrapServers(), Topics.BOOKING_EVENTS)) {
            List<BookingEvent> events = eventsOf(reader, booking.id(), 3);
            assertThat(events).extracting(Object::getClass)
                    .as("события одной брони в одной партиции, порядок сохранён")
                    .containsExactly(BookingCreated.class, PaymentRequested.class, BookingConfirmed.class);
            var request = (PaymentRequested) events.get(1);
            assertThat(request.amount()).isEqualByComparingTo(booking.totalPrice());
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

    private List<BookingEvent> eventsOf(TopicReader reader, Long bookingId, int count) {
        return reader.await(r -> r.key().equals(bookingId.toString()), count, WAIT).stream()
                .map(r -> jsonMapper.readValue(r.value(), BookingEvent.class))
                .toList();
    }

    private int outboxSize() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class);
    }

}
