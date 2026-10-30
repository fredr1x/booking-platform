package dev.ydt.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Сообщение, которое ещё не отправлено в Kafka. См. V6__outbox_and_processed_messages.sql. */
@Entity
@Table(name = "outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;

    @Column(nullable = false, updatable = false)
    private String topic;

    @Column(name = "message_key", nullable = false, updatable = false)
    private String messageKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public OutboxMessage(UUID messageId, String topic, String messageKey, String payload, Instant createdAt) {
        this.messageId = messageId;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.createdAt = createdAt;
    }
}
