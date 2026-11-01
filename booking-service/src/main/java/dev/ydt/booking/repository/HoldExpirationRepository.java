package dev.ydt.booking.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Очередь истечения холдов: sorted set {@code hold-expirations}, где score — момент истечения брони.
 * Уборщик раз в секунду забирает созревшие брони диапазоном по score вместо сканирования таблицы.
 * <p>
 * Не keyspace notifications: Redis шлёт их без гарантий, а события истечения ещё и с задержкой,
 * когда сам доберётся до ключа.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class HoldExpirationRepository {

    static final String KEY = "hold-expirations";

    private final StringRedisTemplate redis;

    public void schedule(Long bookingId, Instant expiresAt) {
        try {
            redis.opsForZSet().add(KEY, bookingId.toString(), expiresAt.toEpochMilli());
        } catch (DataAccessException e) {
            // бронь истечёт по запасному обходу таблицы, просто позже
            log.warn("Could not schedule expiration of booking {}: {}", bookingId, e.getMessage());
        }
    }

    /**
     * Брони, срок которых наступил. Каждую забирает тот инстанс, чей ZREM удалил её из очереди (вернул 1),
     * поэтому несколько уборщиков не обработают одну бронь дважды, как SKIP LOCKED в БД.
     */
    public List<Long> takeDue(Instant now, int limit) {
        try {
            var due = redis.opsForZSet().rangeByScore(KEY, 0, now.toEpochMilli(), 0, limit);
            return Objects.requireNonNull(due).stream()
                    .filter(id -> Long.valueOf(1).equals(redis.opsForZSet().remove(KEY, id)))
                    .map(Long::valueOf)
                    .toList();
        } catch (DataAccessException e) {
            log.warn("Could not read hold expirations: {}", e.getMessage());
            return List.of();
        }
    }
}
