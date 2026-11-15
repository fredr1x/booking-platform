package dev.ydt.booking.repository;

import dev.ydt.booking.entity.SagaState;
import dev.ydt.booking.entity.enums.SagaStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SagaStateRepository extends JpaRepository<SagaState, Long> {

    // Шаг саги и уборщик могут прийти к одной саге одновременно: переход делает тот, кто первым взял строку
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SagaState s where s.bookingId = :bookingId")
    Optional<SagaState> findForUpdate(Long bookingId);

    List<SagaState> findByStatusAndUpdatedAtBefore(SagaStatus status, Instant before, Limit limit);
}
