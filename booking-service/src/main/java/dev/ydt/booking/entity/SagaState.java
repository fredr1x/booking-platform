package dev.ydt.booking.entity;

import dev.ydt.booking.entity.enums.SagaStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Шаг саги оплаты брони. Переходы только вперёд, каждый в транзакции под блокировкой строки. */
@Entity
@Table(name = "saga_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SagaState {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @Column(name = "booking_id")
    private Long bookingId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private String paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static SagaState start(Long bookingId, String paymentId, Instant now) {
        var saga = new SagaState();
        saga.bookingId = bookingId;
        saga.paymentId = paymentId;
        saga.status = SagaStatus.AUTHORIZING;
        saga.updatedAt = now;
        return saga;
    }

    public void complete(Instant now) {
        move(SagaStatus.COMPLETED, now);
    }

    public void startCompensation(String error, Instant now) {
        lastError = truncate(error);
        move(SagaStatus.COMPENSATING, now);
    }

    public void compensated(Instant now) {
        move(SagaStatus.COMPENSATED, now);
    }

    /** Возврат не прошёл: сага остаётся в COMPENSATING, уборщик повторит его позже. */
    public void refundFailed(String error, Instant now) {
        attempts++;
        lastError = truncate(error);
        updatedAt = now;
    }

    private void move(SagaStatus next, Instant now) {
        status = next;
        updatedAt = now;
    }

    private static String truncate(String error) {
        return error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
