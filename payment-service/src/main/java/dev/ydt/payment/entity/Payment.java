package dev.ydt.payment.entity;

import dev.ydt.payment.entity.enums.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Проведённый платёж. Итог не меняется: повторный запрос получает тот же результат и то же сообщение. */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // id платежа приходит от booking-service и уникален: это и есть ключ идемпотентности
    @Column(name = "payment_id", nullable = false, updatable = false)
    private String paymentId;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private Long bookingId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private String userId;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentStatus status;

    @Column(name = "failure_reason", updatable = false)
    private String failureReason;

    // messageId результата: повторная отправка идёт с тем же id, и booking-service узнаёт дубль
    @Column(name = "result_message_id", nullable = false, updatable = false)
    private UUID resultMessageId;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    public static Payment succeeded(String paymentId, Long bookingId, String userId, BigDecimal amount, Instant now) {
        return new Payment(paymentId, bookingId, userId, amount, PaymentStatus.SUCCEEDED, null, now);
    }

    public static Payment failed(String paymentId, Long bookingId, String userId, BigDecimal amount, String reason,
                                 Instant now) {
        return new Payment(paymentId, bookingId, userId, amount, PaymentStatus.FAILED, reason, now);
    }

    private Payment(String paymentId, Long bookingId, String userId, BigDecimal amount, PaymentStatus status,
                    String failureReason, Instant now) {
        this.paymentId = paymentId;
        this.bookingId = bookingId;
        this.userId = userId;
        this.amount = amount;
        this.status = status;
        this.failureReason = failureReason;
        this.resultMessageId = UUID.randomUUID();
        this.processedAt = now;
    }
}
