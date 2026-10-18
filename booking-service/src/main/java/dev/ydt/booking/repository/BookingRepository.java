package dev.ydt.booking.repository;

import dev.ydt.booking.entity.Booking;
import dev.ydt.booking.entity.enums.BookingStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByIdAndUserId(Long id, String userId);

    /**
     * SELECT ... FOR UPDATE SKIP LOCKED: несколько инстансов уборщика берут разные пачки,
     * строки, уже взятые другим инстансом, пропускаются без ожидания. Таймаут -2 Hibernate переводит в SKIP LOCKED.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    List<Booking> findByStatusAndExpiresAtBefore(BookingStatus status, Instant now, Limit limit);
}
