package dev.ydt.booking.repository;

import dev.ydt.booking.entity.Seat;
import dev.ydt.booking.entity.enums.SeatStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByEventIdAndStatusOrderByIdAsc(Long eventId, SeatStatus status, Limit limit);

    List<Seat> findByEventIdAndIdIn(Long eventId, Collection<Long> ids);

    @Query("""
            select s.status as status, count(s) as count
            from Seat s
            where s.eventId = :eventId
            group by s.status
            """)
    List<StatusCount> countByStatus(@Param("eventId") Long eventId);

    /** Безусловная запись статуса, без проверки текущего. Используют NAIVE и JVM_LOCK. */
    @Modifying(flushAutomatically = true)
    @Query("update Seat s set s.status = :status, s.updatedAt = :now where s.id in :ids")
    int updateStatus(@Param("ids") Collection<Long> ids, @Param("status") SeatStatus status,
                     @Param("now") Instant now);

    /**
     * Проверка и запись одним оператором: PostgreSQL перепроверяет WHERE после того, как дождался
     * блокировки строки, поэтому из двух конкурентов место получит только один. Возвращает захолженные id.
     */
    @Query(value = """
            UPDATE seats SET status = 'HELD', version = version + 1, updated_at = :now
            WHERE id IN (:ids) AND status = 'AVAILABLE'
            RETURNING id
            """, nativeQuery = true)
    List<Long> holdIfAvailable(@Param("ids") Collection<Long> ids, @Param("now") Instant now);

    /** Блокирует свободные места, а занятые другой транзакцией пропускает, не дожидаясь её. */
    @Query(value = """
            SELECT id FROM seats
            WHERE id IN (:ids) AND status = 'AVAILABLE'
            ORDER BY id
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockAvailableSkipLocked(@Param("ids") Collection<Long> ids);

    interface StatusCount {
        SeatStatus getStatus();

        long getCount();
    }
}
