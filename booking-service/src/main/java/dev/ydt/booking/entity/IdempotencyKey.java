package dev.ydt.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

// Строки создаются native-запросом INSERT ... ON CONFLICT (см. IdempotencyKeyRepository), сущность только читается.
@Entity
@Table(name = "idempotency_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {

    @EmbeddedId
    private Id id;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "booking_id")
    private Long bookingId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Embeddable
    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor
    public static class Id implements Serializable {

        @Column(name = "user_id", nullable = false)
        private String userId;

        @Column(name = "idempotency_key", nullable = false)
        private String key;
    }
}
