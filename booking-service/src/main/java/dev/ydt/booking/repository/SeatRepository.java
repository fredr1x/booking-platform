package dev.ydt.booking.repository;

import dev.ydt.booking.entity.Seat;
import dev.ydt.booking.entity.enums.SeatStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByEventIdAndStatusOrderByIdAsc(Long eventId, SeatStatus status, Limit limit);

    @Query("""
            select s.status as status, count(s) as count
            from Seat s
            where s.event.id = :eventId
            group by s.status
            """)
    List<StatusCount> countByStatus(@Param("eventId") Long eventId);

    interface StatusCount {
        SeatStatus getStatus();

        long getCount();
    }
}
