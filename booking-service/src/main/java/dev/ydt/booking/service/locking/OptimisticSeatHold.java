package dev.ydt.booking.service.locking;

import dev.ydt.booking.entity.Seat;
import dev.ydt.booking.exception.SeatUnavailableException;
import dev.ydt.booking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Оптимистичная блокировка: ничего не блокируем, а при записи Hibernate добавляет WHERE version = ?.
 * Если место успели поменять после нашего чтения, UPDATE затронет 0 строк, и мы проиграли.
 */
@Component
@RequiredArgsConstructor
class OptimisticSeatHold implements SeatHoldStrategy {

    private final SeatRepository seatRepository;

    @Override
    public SeatLockingMode mode() {
        return SeatLockingMode.OPTIMISTIC;
    }

    @Override
    public void hold(List<Seat> seats, Instant now) {
        seats.forEach(seat -> seat.hold(now));
        try {
            // flush сейчас, чтобы конфликт версий всплыл здесь, а не при коммите
            seatRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            throw new SeatUnavailableException(SeatHoldStrategy.ids(seats));
        }
    }
}
