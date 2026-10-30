package dev.ydt.booking.repository;

import dev.ydt.booking.entity.OutboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    /**
     * Самые старые неотправленные сообщения. SKIP LOCKED: несколько инстансов релея берут разные пачки
     * и не ждут друг друга, как уборщик просроченных броней в блоке 3.
     */
    @Query(value = "SELECT * FROM outbox ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxMessage> lockBatch(int limit);
}
