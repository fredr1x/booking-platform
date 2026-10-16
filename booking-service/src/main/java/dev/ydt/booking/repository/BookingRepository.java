package dev.ydt.booking.repository;

import dev.ydt.booking.entity.Booking;
import dev.ydt.booking.entity.enums.BookingStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByIdAndUserId(Long id, String userId);

    List<Booking> findByStatusAndExpiresAtBefore(BookingStatus status, Instant now, Limit limit);
}
