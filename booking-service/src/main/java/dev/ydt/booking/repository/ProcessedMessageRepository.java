package dev.ydt.booking.repository;

import dev.ydt.booking.entity.ProcessedMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, UUID> {

    /**
     * 1, если сообщение пришло впервые, 0, если уже обработано. Тот же приём, что с Idempotency-Key:
     * параллельная доставка того же сообщения ждёт на первичном ключе, пока первая транзакция не закончится.
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_messages (message_id, processed_at)
            VALUES (:messageId, :now)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int markProcessed(UUID messageId, Instant now);

    @Modifying
    @Query("delete from ProcessedMessage m where m.processedAt < :before")
    int deleteProcessedBefore(Instant before);
}
