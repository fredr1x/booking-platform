package dev.ydt.booking.service;

import dev.ydt.booking.client.PaymentClient;
import dev.ydt.booking.contracts.BookingEvent.BookingCancelled;
import dev.ydt.booking.contracts.BookingEvent.BookingConfirmed;
import dev.ydt.booking.contracts.BookingEvent.PaymentRequested;
import dev.ydt.booking.contracts.payment.PaymentResult;
import dev.ydt.booking.dto.booking.BookingResponse;
import dev.ydt.booking.entity.Booking;
import dev.ydt.booking.entity.SagaState;
import dev.ydt.booking.entity.enums.BookingStatus;
import dev.ydt.booking.entity.enums.CancelReason;
import dev.ydt.booking.entity.enums.SagaStatus;
import dev.ydt.booking.exception.BookingNotFoundException;
import dev.ydt.booking.exception.PaymentUnavailableException;
import dev.ydt.booking.mapper.BookingMapper;
import dev.ydt.booking.repository.BookingRepository;
import dev.ydt.booking.repository.SagaStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Оркестратор саги оплаты. Шаги: места захолжены (при создании брони) → авторизация платежа → подтверждение.
 * <p>
 * Локальные шаги — короткие транзакции, вызов платёжки — между ними, без открытой транзакции: иначе медленная
 * платёжка держала бы соединение из пула на 20. Поэтому шаг записывается в {@code saga_state} до вызова,
 * и после падения сервиса уборщик видит, где остановились.
 * <p>
 * Компенсация в обратном порядке: отменить платёж, освободить места. Освобождение мест локальное и не может
 * не пройти, поэтому делается сразу вместе с отменой брони. Возврат денег удалённый и может не пройти —
 * тогда сага остаётся в COMPENSATING и уборщик повторяет возврат, пока платёжка не ответит.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSagaService {

    private static final int RECOVERY_BATCH_SIZE = 100;

    private final BookingRepository bookingRepository;
    private final SagaStateRepository sagaRepository;
    private final PaymentClient paymentClient;
    private final OutboxService outboxService;
    private final SeatClaimService seatClaimService;
    private final BookingMapper bookingMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    private record PaymentStep(Long bookingId, String paymentId, String userId, BigDecimal amount) {
    }

    /** Ответ после завершения саги: CONFIRMED, или CANCELLED с причиной, если оплата не прошла. */
    public BookingResponse pay(Long id, String userId) {
        // breaker открыт: бронь не трогаем, клиент повторит позже, места остаются за ним до конца холда
        paymentClient.requireAvailable();
        PaymentStep step = transactionTemplate.execute(status -> {
            Booking booking = bookingRepository.findByIdAndUserId(id, userId)
                    .orElseThrow(() -> new BookingNotFoundException(id));
            Instant now = clock.instant();
            booking.startPayment(UUID.randomUUID().toString(), now);
            sagaRepository.save(SagaState.start(booking.getId(), booking.getPaymentId(), now));
            outboxService.publish(new PaymentRequested(UUID.randomUUID(), booking.getId(), userId,
                    booking.getPaymentId(), booking.getTotalPrice(), now));
            return new PaymentStep(booking.getId(), booking.getPaymentId(), userId, booking.getTotalPrice());
        });
        authorize(step);
        return transactionTemplate.execute(status -> bookingMapper.toResponse(bookingRepository.findById(id).orElseThrow()));
    }

    /**
     * Доводит саги, брошенные на полпути: сервис упал во время авторизации или возврат не прошёл.
     * Возвращает число обработанных.
     */
    public int recover(Duration authorizingTimeout, Duration refundRetryDelay) {
        Instant now = clock.instant();
        List<SagaState> stuck = sagaRepository.findByStatusAndUpdatedAtBefore(SagaStatus.AUTHORIZING,
                now.minus(authorizingTimeout), Limit.of(RECOVERY_BATCH_SIZE));
        List<SagaState> unpaidRefunds = sagaRepository.findByStatusAndUpdatedAtBefore(SagaStatus.COMPENSATING,
                now.minus(refundRetryDelay), Limit.of(RECOVERY_BATCH_SIZE));
        for (SagaState saga : stuck) {
            // authorize идемпотентен: если платёж прошёл до падения, вернётся тот же результат
            transactionTemplate.execute(status -> bookingRepository.findById(saga.getBookingId())
                    .map(booking -> new PaymentStep(booking.getId(), saga.getPaymentId(), booking.getUserId(),
                            booking.getTotalPrice())))
                    .ifPresent(this::authorize);
        }
        unpaidRefunds.forEach(saga -> refund(saga.getBookingId(), saga.getPaymentId()));
        return stuck.size() + unpaidRefunds.size();
    }

    private void authorize(PaymentStep step) {
        PaymentResult result;
        try {
            result = paymentClient.authorize(step.paymentId(), step.bookingId(), step.userId(), step.amount());
        } catch (PaymentUnavailableException e) {
            // ответа нет: платёж мог пройти, а мог и нет. Отменяем бронь и отменяем платёж на стороне платёжки
            log.warn("Payment {} for booking {} failed, compensating", step.paymentId(), step.bookingId(), e);
            transition(step.bookingId(), saga -> {
                cancelIfAwaiting(saga.getBookingId(), CancelReason.PAYMENT_UNAVAILABLE);
                saga.startCompensation(rootMessage(e), clock.instant());
            });
            refund(step.bookingId(), step.paymentId());
            return;
        }
        if (!result.succeeded()) {
            // банк отказал: денег не списали, возвращать нечего, достаточно освободить места
            transition(step.bookingId(), saga -> {
                cancelIfAwaiting(saga.getBookingId(), CancelReason.PAYMENT_FAILED);
                saga.compensated(clock.instant());
            });
            return;
        }
        SagaStatus after = transition(step.bookingId(), saga -> {
            Booking booking = bookingRepository.findById(saga.getBookingId()).orElseThrow();
            Instant now = clock.instant();
            if (booking.getStatus() == BookingStatus.AWAITING_PAYMENT) {
                booking.confirm(now);
                outboxService.publish(new BookingConfirmed(UUID.randomUUID(), booking.getId(), booking.getUserId(),
                        saga.getPaymentId(), now));
                saga.complete(now);
            } else {
                // пользователь отменил бронь, пока шла оплата: деньги списаны, их нужно вернуть
                saga.startCompensation("Booking " + booking.getStatus() + " while payment was in progress", now);
            }
        });
        if (after == SagaStatus.COMPENSATING) {
            refund(step.bookingId(), step.paymentId());
        }
    }

    private void refund(Long bookingId, String paymentId) {
        try {
            PaymentResult result = paymentClient.refund(paymentId);
            log.info("Payment {} for booking {} compensated: {}", paymentId, bookingId, result.status());
            transition(bookingId, SagaStatus.COMPENSATING, saga -> saga.compensated(clock.instant()));
        } catch (PaymentUnavailableException e) {
            log.warn("Refund of payment {} for booking {} failed, will retry", paymentId, bookingId);
            transition(bookingId, SagaStatus.COMPENSATING, saga -> saga.refundFailed(rootMessage(e), clock.instant()));
        }
    }

    private SagaStatus transition(Long bookingId, Consumer<SagaState> change) {
        return transition(bookingId, SagaStatus.AUTHORIZING, change);
    }

    // Переход только из ожидаемого шага: если уборщик или параллельный запрос уже продвинул сагу, ничего не делаем.
    // Возвращает шаг после перехода или null, если переход не сделан.
    private SagaStatus transition(Long bookingId, SagaStatus expected, Consumer<SagaState> change) {
        return transactionTemplate.execute(status -> sagaRepository.findForUpdate(bookingId)
                .filter(saga -> saga.getStatus() == expected)
                .map(saga -> {
                    change.accept(saga);
                    return saga.getStatus();
                })
                .orElse(null));
    }

    private void cancelIfAwaiting(Long bookingId, CancelReason reason) {
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        // пользователь мог отменить бронь сам, пока шла оплата: тогда места уже свободны
        if (booking.getStatus() != BookingStatus.AWAITING_PAYMENT) {
            return;
        }
        Instant now = clock.instant();
        booking.cancel(reason, now);
        seatClaimService.releaseSeats(booking.getEventId(),
                booking.getSeats().stream().map(seat -> seat.getSeat().getId()).toList());
        outboxService.publish(new BookingCancelled(UUID.randomUUID(), booking.getId(), booking.getUserId(),
                reason.name(), now));
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.toString();
    }
}
