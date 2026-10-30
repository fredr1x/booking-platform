package dev.ydt.payment.service;

import dev.ydt.booking.contracts.BookingEvent.PaymentRequested;
import dev.ydt.booking.contracts.PaymentEvent;
import dev.ydt.booking.contracts.PaymentEvent.PaymentFailed;
import dev.ydt.booking.contracts.PaymentEvent.PaymentSucceeded;
import dev.ydt.booking.contracts.Topics;
import dev.ydt.payment.config.PaymentProperties;
import dev.ydt.payment.entity.Payment;
import dev.ydt.payment.entity.enums.PaymentStatus;
import dev.ydt.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Заглушка платёжки. Без outbox, но тоже at-least-once: платёж сначала сохраняется, потом результат
 * отправляется синхронно. Упали между этими шагами — Kafka доставит запрос ещё раз, платёж уже найдётся
 * по paymentId, и тот же результат уйдёт повторно, а не спишет деньги второй раз.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final PaymentProperties properties;
    private final Clock clock;

    public void process(PaymentRequested request) {
        Payment payment = paymentRepository.findByPaymentId(request.paymentId())
                .orElseGet(() -> charge(request));
        publish(payment);
    }

    private Payment charge(PaymentRequested request) {
        // shortcut: пауза держит поток consumer'а и его партицию; настоящий провайдер отвечал бы асинхронно (webhook)
        sleep();
        var now = clock.instant();
        boolean declined = ThreadLocalRandom.current().nextDouble() < properties.failureRate();
        var payment = declined
                ? Payment.failed(request.paymentId(), request.bookingId(), request.userId(), request.amount(),
                        "Card declined", now)
                : Payment.succeeded(request.paymentId(), request.bookingId(), request.userId(), request.amount(), now);
        log.info("Payment {} for booking {}: {}", payment.getPaymentId(), payment.getBookingId(), payment.getStatus());
        // уникальный payment_id: параллельный дубль упадёт здесь и при повторе найдёт уже сохранённый платёж
        return paymentRepository.save(payment);
    }

    private void publish(Payment payment) {
        PaymentEvent event = payment.getStatus() == PaymentStatus.SUCCEEDED
                ? new PaymentSucceeded(payment.getResultMessageId(), payment.getBookingId(), payment.getPaymentId(),
                        payment.getProcessedAt())
                : new PaymentFailed(payment.getResultMessageId(), payment.getBookingId(), payment.getPaymentId(),
                        payment.getFailureReason(), payment.getProcessedAt());
        try {
            kafkaTemplate.send(Topics.PAYMENT_EVENTS, payment.getBookingId().toString(),
                    jsonMapper.writeValueAsString(event)).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing payment result", e);
        } catch (ExecutionException | TimeoutException e) {
            // исключение вернёт запрос на повтор: результат уйдёт со следующей попытки
            throw new IllegalStateException("Kafka did not acknowledge payment result", e);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(properties.delay());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while charging", e);
        }
    }
}
