package dev.ydt.booking.service.locking;

import dev.ydt.booking.entity.Seat;
import dev.ydt.booking.repository.SeatRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Проверка и запись одним UPDATE ... WHERE status = 'AVAILABLE'. Окна между ними нет вообще,
 * один запрос в БД вместо чтения с блокировкой и записи. Режим по умолчанию.
 */
@Component
@RequiredArgsConstructor
class AtomicSeatHold implements SeatHoldStrategy {

    private final SeatRepository seatRepository;
    private final EntityManager entityManager;

    @Override
    public SeatLockingMode mode() {
        return SeatLockingMode.ATOMIC;
    }

    @Override
    public void hold(List<Seat> seats, Instant now) {
        List<Long> requested = SeatHoldStrategy.ids(seats);
        // если захолдили не все места, исключение откатит транзакцию вместе с частичным UPDATE
        SeatHoldStrategy.requireAll(requested, seatRepository.holdIfAvailable(requested, now));
        seats.forEach(entityManager::refresh);
    }
}
