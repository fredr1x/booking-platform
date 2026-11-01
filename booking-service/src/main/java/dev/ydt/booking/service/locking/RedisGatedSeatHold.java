package dev.ydt.booking.service.locking;

import dev.ydt.booking.repository.SeatRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/**
 * Запись в БД та же, что у ATOMIC: claim в Redis делает BookingService до транзакции,
 * а атомарный UPDATE остаётся страховкой на случай, если Redis недоступен или ключ истёк.
 */
@Component
class RedisGatedSeatHold extends AtomicSeatHold {

    RedisGatedSeatHold(SeatRepository seatRepository, EntityManager entityManager) {
        super(seatRepository, entityManager);
    }

    @Override
    public SeatLockingMode mode() {
        return SeatLockingMode.REDIS;
    }
}
