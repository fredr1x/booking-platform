package dev.ydt.notification.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Отправленное уведомление. {@code _id} — messageId события: второй документ с тем же id MongoDB не вставит,
 * поэтому повторная доставка не отправит уведомление второй раз. Отдельная коллекция processed_messages не нужна.
 */
@Document("notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    private String id;

    @Indexed
    private Long bookingId;

    private String userId;

    private String type;

    private String text;

    private Instant createdAt;

    public Notification(String id, Long bookingId, String userId, String type, String text, Instant createdAt) {
        this.id = id;
        this.bookingId = bookingId;
        this.userId = userId;
        this.type = type;
        this.text = text;
        this.createdAt = createdAt;
    }
}
