package dev.ydt.booking.service.locking;

import dev.ydt.booking.entity.Seat;
import dev.ydt.booking.entity.enums.SeatStatus;
import dev.ydt.booking.exception.SeatUnavailableException;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Переводит места в HELD внутри текущей транзакции или бросает {@link SeatUnavailableException}.
 * На входе места, прочитанные без блокировок: пока мы их читали, их мог занять кто-то другой.
 */
public interface SeatHoldStrategy {

    SeatLockingMode mode();

    void hold(List<Seat> seats, Instant now);

    static List<Long> ids(Collection<Seat> seats) {
        return seats.stream().map(Seat::getId).toList();
    }

    static void requireAvailable(Collection<Seat> seats) {
        List<Long> taken = seats.stream()
                .filter(seat -> seat.getStatus() != SeatStatus.AVAILABLE)
                .map(Seat::getId)
                .toList();
        if (!taken.isEmpty()) {
            throw new SeatUnavailableException(taken);
        }
    }

    static void requireAll(List<Long> requested, Collection<Long> held) {
        if (held.size() != requested.size()) {
            throw new SeatUnavailableException(requested.stream().filter(id -> !held.contains(id)).toList());
        }
    }
}
