package dev.ydt.booking.entity;

import dev.ydt.booking.entity.enums.SeatStatus;
import dev.ydt.booking.exception.SeatUnavailableException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "seats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(nullable = false)
    private String section;

    @Column(name = "row_label", nullable = false)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    @Column(nullable = false)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // Наивная проверка без блокировок: гонку между проверкой и записью разбираем в блоке 3.
    public void hold(Instant now) {
        if (status != SeatStatus.AVAILABLE) {
            throw new SeatUnavailableException(List.of(id));
        }
        changeStatus(SeatStatus.HELD, now);
    }

    public void book(Instant now) {
        requireStatus(SeatStatus.HELD);
        changeStatus(SeatStatus.BOOKED, now);
    }

    public void release(Instant now) {
        if (status == SeatStatus.AVAILABLE) {
            throw new IllegalStateException("Seat " + id + " is already available");
        }
        changeStatus(SeatStatus.AVAILABLE, now);
    }

    private void requireStatus(SeatStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Seat " + id + " is " + status + ", expected " + expected);
        }
    }

    private void changeStatus(SeatStatus next, Instant now) {
        status = next;
        updatedAt = now;
    }
}
