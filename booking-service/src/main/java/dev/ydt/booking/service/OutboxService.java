package dev.ydt.booking.service;

import dev.ydt.booking.contracts.BookingEvent;
import dev.ydt.booking.contracts.Topics;
import dev.ydt.booking.contracts.web.RequestIdFilter;
import dev.ydt.booking.entity.OutboxMessage;
import dev.ydt.booking.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Transactional outbox. Почему не {@code kafkaTemplate.send} прямо в сервисе: отправка в Kafka и коммит в Postgres
 * не атомарны. Отправили и упали до коммита — в Kafka событие о брони, которой нет. Закоммитили и упали
 * до отправки — бронь есть, а оплата о ней никогда не узнает.
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    public static final int BATCH_SIZE = 100;
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /** Только внутри транзакции, которая меняет бронь: без неё событие и изменение разъедутся. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(BookingEvent event) {
        outboxRepository.save(new OutboxMessage(event.messageId(), Topics.BOOKING_EVENTS,
                event.bookingId().toString(), jsonMapper.writeValueAsString(event), clock.instant(),
                MDC.get(RequestIdFilter.MDC_KEY)));
    }

    /**
     * Отправляет одну пачку и удаляет её после подтверждения брокера (acks=all). Если брокер не ответил,
     * транзакция откатывается, строки остаются и уйдут в следующий раз. Часть из них могла уже дойти:
     * отсюда дубли, которые consumer отсекает по messageId.
     *
     * @return сколько сообщений отправлено
     */
    // shortcut: транзакция и блокировка строк держатся, пока Kafka подтверждает пачку (до 10 с);
    // при медленном брокере стоит помечать строки «в отправке» и отпускать транзакцию
    @Transactional
    public int relayBatch() {
        var batch = outboxRepository.lockBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return 0;
        }
        var sends = batch.stream()
                .map(message -> kafkaTemplate.send(toRecord(message)))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(sends).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while relaying outbox", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka did not acknowledge outbox batch", e);
        }
        outboxRepository.deleteAllInBatch(batch);
        return batch.size();
    }

    private static ProducerRecord<String, String> toRecord(OutboxMessage message) {
        var record = new ProducerRecord<String, String>(message.getTopic(), message.getMessageKey(), message.getPayload());
        if (message.getRequestId() != null) {
            record.headers().add(RequestIdFilter.HEADER, message.getRequestId().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
