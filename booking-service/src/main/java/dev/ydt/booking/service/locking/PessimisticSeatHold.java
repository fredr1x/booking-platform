package dev.ydt.booking.service.locking;

import dev.ydt.booking.entity.Seat;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * SELECT ... FOR UPDATE на каждое место: конкуренты встают в очередь на блокировку строки
 * и после нашего коммита видят место уже занятым.
 */
@Component
@RequiredArgsConstructor
class PessimisticSeatHold implements SeatHoldStrategy {

    private final EntityManager entityManager;

    @Override
    public SeatLockingMode mode() {
        return SeatLockingMode.PESSIMISTIC;
    }

    @Override
    public void hold(List<Seat> seats, Instant now) {
        // Блокируем всегда в порядке id: две брони {1, 2} и {2, 1} иначе ждали бы друг друга вечно (deadlock).
        // refresh перечитывает строку под блокировкой, данные из первого чтения могли устареть.
        seats.stream()
                .sorted(Comparator.comparing(Seat::getId))
                .forEach(seat -> entityManager.refresh(seat, LockModeType.PESSIMISTIC_WRITE));
        SeatHoldStrategy.requireAvailable(seats);
        seats.forEach(seat -> seat.hold(now));
    }
}
