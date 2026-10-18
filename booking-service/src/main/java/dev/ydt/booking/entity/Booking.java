package dev.ydt.booking.entity;

import dev.ydt.booking.entity.enums.BookingStatus;
import dev.ydt.booking.entity.enums.CancelReason;
import dev.ydt.booking.entity.enums.PricingType;
import dev.ydt.booking.entity.enums.SeatStatus;
import dev.ydt.booking.entity.state.BookingState;
import dev.ydt.booking.entity.state.BookingState.AwaitingPayment;
import dev.ydt.booking.entity.state.BookingState.Cancelled;
import dev.ydt.booking.entity.state.BookingState.Confirmed;
import dev.ydt.booking.entity.state.BookingState.Expired;
import dev.ydt.booking.entity.state.BookingState.Pending;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Агрегат брони. В БД состояние хранится плоскими колонками, в коде собирается в sealed {@link BookingState}.
 * Менять статус можно только через методы переходов, сеттеров нет.
 */
@Entity
@Table(name = "bookings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_type", nullable = false)
    private PricingType pricingType;

    @Column(name = "total_price", nullable = false)
    private BigDecimal totalPrice;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "payment_id")
    private String paymentId;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason")
    private CancelReason cancelReason;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BookingSeat> seats = new ArrayList<>();

    /**
     * Создаёт бронь в статусе PENDING на уже захолженные места. Холд делает {@code SeatHoldStrategy}:
     * от способа блокировки зависит, защищены ли мы от гонки, поэтому это не забота агрегата.
     *
     * @param seatPrices итоговая цена каждого места по id, посчитанная стратегией ценообразования
     */
    public static Booking create(Event event, String userId, List<Seat> seats, PricingType pricingType,
                                 Map<Long, BigDecimal> seatPrices, Instant now, Duration holdTtl) {
        Booking booking = new Booking();
        booking.event = event;
        booking.userId = userId;
        booking.pricingType = pricingType;
        booking.createdAt = now;
        booking.totalPrice = BigDecimal.ZERO;
        for (Seat seat : seats) {
            BigDecimal price = seatPrices.get(seat.getId());
            if (price == null) {
                throw new IllegalArgumentException("No price for seat " + seat.getId());
            }
            if (seat.getStatus() != SeatStatus.HELD) {
                throw new IllegalStateException("Seat " + seat.getId() + " must be held before booking");
            }
            booking.seats.add(new BookingSeat(booking, seat, price));
            booking.totalPrice = booking.totalPrice.add(price);
        }
        booking.apply(new Pending(now.plus(holdTtl)), now);
        return booking;
    }

    public BookingState getState() {
        return switch (status) {
            case PENDING -> new Pending(expiresAt);
            case AWAITING_PAYMENT -> new AwaitingPayment(paymentId);
            case CONFIRMED -> new Confirmed(paymentId, confirmedAt);
            case CANCELLED -> new Cancelled(cancelReason, closedAt);
            case EXPIRED -> new Expired(closedAt);
        };
    }

    public List<BookingSeat> getSeats() {
        return Collections.unmodifiableList(seats);
    }

    public boolean isOwnedBy(String userId) {
        return this.userId.equals(userId);
    }

    public void startPayment(String paymentId, Instant now) {
        apply(getState().startPayment(paymentId, now), now);
    }

    public void confirm(Instant now) {
        apply(getState().confirm(now), now);
        seats.forEach(s -> s.getSeat().book(now));
    }

    public void cancel(CancelReason reason, Instant now) {
        apply(getState().cancel(reason, now), now);
        releaseSeats(now);
    }

    public void expire(Instant now) {
        apply(getState().expire(now), now);
        releaseSeats(now);
    }

    private void releaseSeats(Instant now) {
        seats.forEach(s -> s.release(now));
    }

    // Раскладывает состояние по колонкам. Switch по sealed-типу без default: новое состояние не скомпилируется,
    // пока его не добавят сюда.
    private void apply(BookingState next, Instant now) {
        status = next.status();
        updatedAt = now;
        switch (next) {
            case Pending p -> expiresAt = p.expiresAt();
            case AwaitingPayment a -> paymentId = a.paymentId();
            case Confirmed c -> confirmedAt = c.confirmedAt();
            case Cancelled c -> {
                cancelReason = c.reason();
                closedAt = c.cancelledAt();
            }
            case Expired e -> closedAt = e.expiredAt();
        }
    }
}
