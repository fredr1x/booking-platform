package dev.ydt.waitingroom.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Очередь: sorted set, где score — момент входа. Позиция — ZRANK, пропуск — ZPOPMIN. */
@Repository
@RequiredArgsConstructor
public class QueueRepository {

    private static final RedisScript<Long> ADMIT = RedisScript.of(new ClassPathResource("scripts/admit.lua"), Long.class);

    private final StringRedisTemplate redis;

    /** NX: повторный вход не отправляет в конец очереди. */
    public void join(long eventId, String userId, Instant now) {
        redis.opsForZSet().addIfAbsent(queueKey(eventId), userId, now.toEpochMilli());
    }

    /** @return сколько человек впереди, null — пользователя нет в очереди */
    public Long rank(long eventId, String userId) {
        return redis.opsForZSet().rank(queueKey(eventId), userId);
    }

    /** @return сколько ещё действует пропуск в мс; не больше нуля, если пропуска нет */
    public long admissionTtlMillis(long eventId, String userId) {
        return redis.getExpire(admittedPrefix(eventId) + userId, TimeUnit.MILLISECONDS);
    }

    /** @return сколько человек пропущено; 0, если проход этого интервала уже сделал другой инстанс */
    public long admit(long eventId, int count, Duration admissionTtl, Duration tickTtl) {
        return redis.execute(ADMIT, List.of(queueKey(eventId), "admit-tick:{" + eventId + "}"), String.valueOf(count),
                String.valueOf(admissionTtl.toMillis()), String.valueOf(tickTtl.toMillis()), admittedPrefix(eventId));
    }

    // {eventId} — hash tag: скрипт работает с ключами одного события, в Redis Cluster им нужен один слот
    private static String queueKey(long eventId) {
        return "queue:{" + eventId + "}";
    }

    private static String admittedPrefix(long eventId) {
        return "admitted:{" + eventId + "}:";
    }
}
