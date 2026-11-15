package dev.ydt.payment.repository;

import dev.ydt.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentId(String paymentId);

    /** Первый результат по paymentId выигрывает: повтор и отменённый раньше платёж ничего не перезапишут. */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO payments (payment_id, booking_id, user_id, amount, status, failure_reason, processed_at)
            VALUES (:paymentId, :bookingId, :userId, :amount, :status, :failureReason, :now)
            ON CONFLICT (payment_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(String paymentId, Long bookingId, String userId, BigDecimal amount, String status,
                       String failureReason, Instant now);

    /** Возврат проведённого платежа. Повтор ничего не меняет: статус уже REFUNDED. */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE payments SET status = 'REFUNDED', processed_at = :now
            WHERE payment_id = :paymentId AND status = 'SUCCEEDED'
            """, nativeQuery = true)
    int refundSucceeded(String paymentId, Instant now);
}
