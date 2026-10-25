package dev.ydt.booking.service.locking;

import dev.ydt.booking.entity.Seat;
import dev.ydt.booking.entity.enums.SeatStatus;
import dev.ydt.booking.repository.SeatRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Блокировка в памяти JVM, одна на событие. Внутри одного процесса брони события идут строго по очереди,
 * но второй инстанс сервиса держит свои блокировки и о наших не знает.
 * ReentrantLock, а не synchronized: на Java 21 synchronized «приклеивает» виртуальный поток к потоку ОС.
 */
@Component
@RequiredArgsConstructor
class JvmLockSeatHold implements SeatHoldStrategy {

    private final SeatRepository seatRepository;
    private final EntityManager entityManager;
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public SeatLockingMode mode() {
        return SeatLockingMode.JVM_LOCK;
    }

    @Override
    public void hold(List<Seat> seats, Instant now) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("JVM lock must be taken inside a transaction");
        }
        ReentrantLock lock = locks.computeIfAbsent(seats.getFirst().getEventId(), id -> new ReentrantLock());
        lock.lock();
        // Отпускаем после коммита, а не в конце метода: иначе следующий поток прочитает места
        // до того, как наша запись станет видна, и гонка вернётся даже на одном инстансе.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                lock.unlock();
            }
        });

        seats.forEach(entityManager::refresh);
        SeatHoldStrategy.requireAvailable(seats);
        seatRepository.updateStatus(SeatHoldStrategy.ids(seats), SeatStatus.HELD, now);
        seats.forEach(entityManager::refresh);
    }
}
