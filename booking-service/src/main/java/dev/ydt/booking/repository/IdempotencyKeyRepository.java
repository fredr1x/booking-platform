package dev.ydt.booking.repository;

import dev.ydt.booking.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, IdempotencyKey.Id> {

    /**
     * Возвращает 1, если ключ новый, и 0, если он уже есть. Пока транзакция с тем же ключом не закончилась,
     * PostgreSQL держит этот INSERT на первичном ключе: дубль ждёт и потом видит готовую бронь.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, created_at)
            VALUES (:userId, :key, :requestHash, :now)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(String userId, String key, String requestHash, Instant now);

    @Modifying
    @Query("update IdempotencyKey k set k.bookingId = :bookingId where k.id = :id")
    void attachBooking(IdempotencyKey.Id id, Long bookingId);

    @Modifying
    @Query("delete from IdempotencyKey k where k.createdAt < :before")
    int deleteCreatedBefore(Instant before);
}
