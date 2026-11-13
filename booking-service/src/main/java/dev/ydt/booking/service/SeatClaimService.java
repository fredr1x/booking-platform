package dev.ydt.booking.service;

import dev.ydt.booking.exception.SeatUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * Claim мест в Redis до транзакции: Lua-скрипт {@code claim-seats.lua}. Из тысячи покупателей
 * одного места до PostgreSQL доходит один, остальные получают 409, не взяв соединение из пула.
 * <p>
 * Redis здесь фильтр, а не источник правды: место продано, только когда атомарный UPDATE в БД прошёл.
 * Поэтому при недоступном Redis бронь не падает, а работает как раньше, через одну БД (fail-open).
 */
@Slf4j
@Service
public class SeatClaimService {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CLAIM =
            RedisScript.of(new ClassPathResource("scripts/claim-seats.lua"), List.class);

    private final StringRedisTemplate redis;
    private final Counter rejected;

    public SeatClaimService(StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.rejected = Counter.builder("booking.seat.claim.rejected")
                .description("Seat requests rejected by the Redis claim before reaching the database")
                .register(meterRegistry);
    }

    /**
     * Все места одним Lua-скриптом: Redis выполняет его целиком, поэтому либо поставлены все ключи, либо ни одного.
     * Покупатели мест {1, 2} и {2, 1} не могут захватить по одному и отказать друг другу оба.
     *
     * @param owner владелец claim'а. Повтор с тем же Idempotency-Key передаёт того же владельца
     *              и проходит дальше, к повтору ответа, а не получает 409 на свои же места
     * @return ключи, поставленные этим вызовом: их снимает {@link #release(Collection)}, если бронь не создалась
     * @throws SeatUnavailableException место уже занято другим владельцем
     */
    @SuppressWarnings("unchecked")
    public List<String> claim(Long eventId, Collection<Long> seatIds, String owner, Duration ttl) {
        List<Long> seats = List.copyOf(seatIds);
        List<String> keys = seats.stream().map(seatId -> key(eventId, seatId)).toList();
        try {
            List<String> result = redis.execute(CLAIM, keys, owner, String.valueOf(ttl.toMillis()));
            if ("BUSY".equals(result.getFirst())) {
                rejected.increment();
                throw new SeatUnavailableException(List.of(seats.get(Integer.parseInt(result.get(1)) - 1)));
            }
            return result.subList(1, result.size());
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, seats are guarded by the database only: {}", e.getMessage());
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
        // {eventId} — hash tag: в Redis Cluster ключи одного события лягут в один слот, иначе скрипт с ними не выполнится
        return "seat:{" + eventId + "}:" + seatId;
    }
}
