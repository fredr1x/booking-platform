package dev.ydt.booking.service;

import dev.ydt.booking.exception.SeatUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Claim мест в Redis до транзакции: {@code SET seat:{eventId}:{seatId} owner NX PX ttl}. Из тысячи покупателей
 * одного места до PostgreSQL доходит один, остальные получают 409, не взяв соединение из пула.
 * <p>
 * Redis здесь фильтр, а не источник правды: место продано, только когда атомарный UPDATE в БД прошёл.
 * Поэтому при недоступном Redis бронь не падает, а работает как раньше, через одну БД (fail-open).
 */
@Slf4j
@Service
public class SeatClaimService {

    private final StringRedisTemplate redis;
    private final Counter rejected;

    public SeatClaimService(StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.rejected = Counter.builder("booking.seat.claim.rejected")
                .description("Seat requests rejected by the Redis claim before reaching the database")
                .register(meterRegistry);
    }

    /**
     * Ставит ключи по возрастанию id: два покупателя мест {1, 2} и {2, 1} столкнутся на первом же месте,
     * а не захватят по одному и не откажут друг другу оба.
     *
     * @param owner владелец claim'а. Повтор с тем же Idempotency-Key передаёт того же владельца
     *              и проходит дальше, к повтору ответа, а не получает 409 на свои же места
     * @return ключи, поставленные этим вызовом: их снимает {@link #release(Collection)}, если бронь не создалась
     * @throws SeatUnavailableException место уже занято другим владельцем
     */
    public List<String> claim(Long eventId, Collection<Long> seatIds, String owner, Duration ttl) {
        var claimed = new ArrayList<String>();
        try {
            for (Long seatId : seatIds.stream().sorted().toList()) {
                String key = key(eventId, seatId);
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, ttl))) {
                    claimed.add(key);
                } else if (!owner.equals(redis.opsForValue().get(key))) {
                    release(claimed);
                    rejected.increment();
                    throw new SeatUnavailableException(List.of(seatId));
                }
            }
            return claimed;
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, seats are guarded by the database only: {}", e.getMessage());
            release(claimed);
            return List.of();
        }
    }

    /** Освобождает места брони, которая отменена или истекла: их можно бронить сразу, не дожидаясь TTL. */
    public void releaseSeats(Long eventId, Collection<Long> seatIds) {
        release(seatIds.stream().map(seatId -> key(eventId, seatId)).toList());
    }

    public void release(Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        try {
            redis.delete(keys);
        } catch (DataAccessException e) {
            // ключи доживут до TTL: места будут отвергаться в Redis, хотя в БД свободны, но не продадутся дважды
            log.warn("Could not release seat claims {}: {}", keys, e.getMessage());
        }
    }

    private static String key(Long eventId, Long seatId) {
        return "seat:" + eventId + ":" + seatId;
    }
}
