package dev.ydt.booking.service.locking;

import dev.ydt.booking.entity.Seat;
import dev.ydt.booking.entity.enums.SeatStatus;
import dev.ydt.booking.repository.SeatRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Check-then-act: статус проверили при чтении, а пишем безусловно. Между проверкой и записью другой
 * запрос успевает прочитать то же «свободно», и место продаётся дважды. Спасает только уникальный индекс.
 */
@Component
@RequiredArgsConstructor
class NaiveSeatHold implements SeatHoldStrategy {

    private final SeatRepository seatRepository;
    private final EntityManager entityManager;

    @Override
    public SeatLockingMode mode() {
        return SeatLockingMode.NAIVE;
    }

    @Override
    public void hold(List<Seat> seats, Instant now) {
        seatRepository.updateStatus(SeatHoldStrategy.ids(seats), SeatStatus.HELD, now);
        seats.forEach(entityManager::refresh);
    }
}
