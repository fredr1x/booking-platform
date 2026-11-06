package dev.ydt.notification.entity;

import dev.ydt.notification.entity.enums.BookingStatus;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read-модель (CQRS): одна бронь в истории пользователя, собранная из событий booking.events.
 * {@code _id} — id брони. Документ только обновляется событиями, источник правды — БД booking-service.
 * <p>
 * {@code statusAt} — время события, которое поставило текущий статус: событие старше него статус не меняет.
 * Так история верна, даже если события пришли не по порядку (одно ушло в retry-топик, следующее его обогнало).
 */
@Document("booking_history")
@CompoundIndex(name = "user_created", def = "{'userId': 1, 'createdAt': -1}")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookingHistory {

    @Id
    private Long id;

    private String userId;

    private Long eventId;

    private BigDecimal totalPrice;

    private BookingStatus status;

    private Instant statusAt;

    private String paymentId;

    private String cancelReason;

    private Instant expiresAt;

    private Instant createdAt;
}
