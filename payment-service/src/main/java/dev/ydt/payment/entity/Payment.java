package dev.ydt.payment.entity;

import dev.ydt.booking.contracts.payment.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Платёж заглушки. Пишется и меняется только запросами {@code PaymentRepository} с условием на текущий статус:
 * так параллельные authorize и refund одного платежа не перетирают друг друга.
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    private Long id;

    // id платежа выдаёт booking-service, он же ключ идемпотентности
    @Column(name = "payment_id")
    private String paymentId;

    @Column(name = "booking_id")
    private Long bookingId;

    @Column(name = "user_id")
    private String userId;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "processed_at")
    private Instant processedAt;
}
