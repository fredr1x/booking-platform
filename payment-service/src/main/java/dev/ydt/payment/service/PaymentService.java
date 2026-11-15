package dev.ydt.payment.service;

import dev.ydt.booking.contracts.payment.AuthorizePaymentRequest;
import dev.ydt.booking.contracts.payment.PaymentResult;
import dev.ydt.booking.contracts.payment.PaymentStatus;
import dev.ydt.payment.config.PaymentProperties;
import dev.ydt.payment.entity.Payment;
import dev.ydt.payment.exception.PaymentProviderException;
import dev.ydt.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Заглушка платёжки. Каждый вызов идемпотентен по paymentId: повтор authorize после таймаута вернёт уже
 * сохранённый результат, а не спишет деньги второй раз.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentProperties properties;
    private final Clock clock;

    public PaymentResult authorize(String paymentId, AuthorizePaymentRequest request) {
        var known = paymentRepository.findByPaymentId(paymentId);
        if (known.isPresent()) {
            return toResult(known.get());
        }
        // пауза до записи: соединение с БД на время «банка» не держится
        sleep();
        var random = ThreadLocalRandom.current();
        if (random.nextDouble() < properties.errorRate()) {
            throw new PaymentProviderException();
        }
        boolean declined = random.nextDouble() < properties.failureRate();
        // если за время паузы платёж отменили (refund пришёл раньше), строка VOIDED уже есть и вставка не пройдёт
        paymentRepository.insertIfAbsent(paymentId, request.bookingId(), request.userId(), request.amount(),
                (declined ? PaymentStatus.FAILED : PaymentStatus.SUCCEEDED).name(),
                declined ? "Card declined" : null, clock.instant());
        var payment = paymentRepository.findByPaymentId(paymentId).orElseThrow();
        log.info("Payment {} for booking {}: {}", paymentId, request.bookingId(), payment.getStatus());
        return toResult(payment);
    }

    /**
     * Возврат или отмена. Платежа ещё нет (authorize не дошёл или ещё идёт) — пишем VOIDED, и запоздавший
     * authorize его уже не проведёт. Отказ банка возвращать не нужно, статус остаётся FAILED.
     */
    public PaymentResult refund(String paymentId) {
        var now = clock.instant();
        if (paymentRepository.insertIfAbsent(paymentId, null, null, null, PaymentStatus.VOIDED.name(), null, now) == 0) {
            paymentRepository.refundSucceeded(paymentId, now);
        }
        var payment = paymentRepository.findByPaymentId(paymentId).orElseThrow();
        log.info("Refund of payment {}: {}", paymentId, payment.getStatus());
        return toResult(payment);
    }

    private static PaymentResult toResult(Payment payment) {
        return new PaymentResult(payment.getPaymentId(), payment.getStatus(), payment.getFailureReason());
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
