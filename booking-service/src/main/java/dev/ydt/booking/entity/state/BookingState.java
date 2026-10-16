package dev.ydt.booking.entity.state;

import dev.ydt.booking.entity.enums.BookingStatus;
import dev.ydt.booking.entity.enums.CancelReason;
import dev.ydt.booking.exception.IllegalBookingTransitionException;

import java.time.Instant;
import java.util.Objects;

/**
 * Состояние брони (паттерн State на sealed-типах). Каждое состояние несёт только свои данные
 * и переопределяет только разрешённые переходы, всё остальное бросает исключение.
 *
 * <pre>
 * PENDING ──startPayment──► AWAITING_PAYMENT ──confirm──► CONFIRMED
 *    │ └──expire──► EXPIRED          │                        │
 *    └──cancel──► CANCELLED ◄──cancel┘◄────────cancel─────────┘
 * </pre>
 */
public sealed interface BookingState {

    BookingStatus status();

    default BookingState startPayment(String paymentId, Instant now) {
        throw illegal(BookingAction.START_PAYMENT);
    }

    default BookingState confirm(Instant now) {
        throw illegal(BookingAction.CONFIRM);
    }

    default BookingState cancel(CancelReason reason, Instant now) {
        throw illegal(BookingAction.CANCEL);
    }

    default BookingState expire(Instant now) {
        throw illegal(BookingAction.EXPIRE);
    }

    private IllegalBookingTransitionException illegal(BookingAction action) {
        return new IllegalBookingTransitionException(status(), action);
    }

    /** Места захолжены, ждём оплату до {@code expiresAt}. */
    record Pending(Instant expiresAt) implements BookingState {

        public Pending {
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        @Override
        public BookingStatus status() {
            return BookingStatus.PENDING;
        }

        @Override
        public BookingState startPayment(String paymentId, Instant now) {
            if (!now.isBefore(expiresAt)) {
                throw new IllegalBookingTransitionException(status(), BookingAction.START_PAYMENT,
                        "hold expired at " + expiresAt);
            }
            return new AwaitingPayment(paymentId);
        }

        @Override
        public BookingState cancel(CancelReason reason, Instant now) {
            return new Cancelled(reason, now);
        }

        @Override
        public BookingState expire(Instant now) {
            if (now.isBefore(expiresAt)) {
                throw new IllegalBookingTransitionException(status(), BookingAction.EXPIRE,
                        "hold is valid until " + expiresAt);
            }
            return new Expired(now);
        }
    }

    /** Платёж создан, ждём результат от платёжки. */
    record AwaitingPayment(String paymentId) implements BookingState {

        public AwaitingPayment {
            Objects.requireNonNull(paymentId, "paymentId");
        }

        @Override
        public BookingStatus status() {
            return BookingStatus.AWAITING_PAYMENT;
        }

        @Override
        public BookingState confirm(Instant now) {
            return new Confirmed(paymentId, now);
        }

        @Override
        public BookingState cancel(CancelReason reason, Instant now) {
            return new Cancelled(reason, now);
        }
    }

    /** Оплачено, места выкуплены. Отмена здесь означает возврат. */
    record Confirmed(String paymentId, Instant confirmedAt) implements BookingState {

        public Confirmed {
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(confirmedAt, "confirmedAt");
        }

        @Override
        public BookingStatus status() {
            return BookingStatus.CONFIRMED;
        }

        @Override
        public BookingState cancel(CancelReason reason, Instant now) {
            return new Cancelled(reason, now);
        }
    }

    /** Конечное состояние. */
    record Cancelled(CancelReason reason, Instant cancelledAt) implements BookingState {

        public Cancelled {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(cancelledAt, "cancelledAt");
        }

        @Override
        public BookingStatus status() {
            return BookingStatus.CANCELLED;
        }
    }

    /** Конечное состояние: холд истёк без оплаты. */
    record Expired(Instant expiredAt) implements BookingState {

        public Expired {
            Objects.requireNonNull(expiredAt, "expiredAt");
        }

        @Override
        public BookingStatus status() {
            return BookingStatus.EXPIRED;
        }
    }
}
