package dev.ydt.booking.service.locking;

import dev.ydt.booking.entity.Seat;
import dev.ydt.booking.repository.SeatRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Как PESSIMISTIC, но без ожидания: место, которое прямо сейчас бронирует другая транзакция,
 * сразу считается занятым. Соединения с БД не простаивают в очереди на блокировку.
 */
@Component
@RequiredArgsConstructor
class SkipLockedSeatHold implements SeatHoldStrategy {

    private final SeatRepository seatRepository;
    private final EntityManager entityManager;

    @Override
    public SeatLockingMode mode() {
        return SeatLockingMode.SKIP_LOCKED;
    }

    @Override
    public void hold(List<Seat> seats, Instant now) {
        List<Long> requested = SeatHoldStrategy.ids(seats);
        SeatHoldStrategy.requireAll(requested, seatRepository.lockAvailableSkipLocked(requested));
        // строки уже заблокированы нами, перечитываем актуальную версию, чтобы UPDATE не упал на @Version
        seats.forEach(entityManager::refresh);
        seats.forEach(seat -> seat.hold(now));
    }
}
