package dev.ydt.booking.entity.state;

import dev.ydt.booking.entity.enums.BookingStatus;
import dev.ydt.booking.entity.enums.CancelReason;
import dev.ydt.booking.entity.state.BookingState.AwaitingPayment;
import dev.ydt.booking.entity.state.BookingState.Cancelled;
import dev.ydt.booking.entity.state.BookingState.Confirmed;
import dev.ydt.booking.entity.state.BookingState.Expired;
import dev.ydt.booking.entity.state.BookingState.Pending;
import dev.ydt.booking.exception.IllegalBookingTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.stream.Stream;

import static dev.ydt.booking.entity.enums.BookingStatus.AWAITING_PAYMENT;
import static dev.ydt.booking.entity.enums.BookingStatus.CANCELLED;
import static dev.ydt.booking.entity.enums.BookingStatus.CONFIRMED;
import static dev.ydt.booking.entity.enums.BookingStatus.EXPIRED;
import static dev.ydt.booking.entity.state.BookingAction.CANCEL;
import static dev.ydt.booking.entity.state.BookingAction.CONFIRM;
import static dev.ydt.booking.entity.state.BookingAction.EXPIRE;
import static dev.ydt.booking.entity.state.BookingAction.START_PAYMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class BookingStateTest {

    private static final Instant CREATED = Instant.parse("2026-10-16T10:00:00Z");
    private static final Instant EXPIRES = CREATED.plusSeconds(600);
    private static final Instant AFTER_EXPIRY = EXPIRES.plusSeconds(1);

    private static final Pending PENDING = new Pending(EXPIRES);
    private static final AwaitingPayment AWAITING = new AwaitingPayment("pay-1");
    private static final Confirmed CONFIRMED_STATE = new Confirmed("pay-1", CREATED);
    private static final Cancelled CANCELLED_STATE = new Cancelled(CancelReason.USER_REQUEST, CREATED);
    private static final Expired EXPIRED_STATE = new Expired(AFTER_EXPIRY);

    /**
     * Полная матрица: 5 состояний x 4 действия. {@code null} в ожидаемом статусе = переход запрещён.
     * Время действия подобрано так, чтобы запрет был именно из-за состояния, а не из-за срока холда.
     */
    static Stream<Arguments> transitions() {
        return Stream.of(
                arguments(PENDING, START_PAYMENT, CREATED, AWAITING_PAYMENT),
                arguments(PENDING, CONFIRM, CREATED, null),
                arguments(PENDING, CANCEL, CREATED, CANCELLED),
                arguments(PENDING, EXPIRE, AFTER_EXPIRY, EXPIRED),

                arguments(AWAITING, START_PAYMENT, CREATED, null),
                arguments(AWAITING, CONFIRM, CREATED, CONFIRMED),
                arguments(AWAITING, CANCEL, CREATED, CANCELLED),
                arguments(AWAITING, EXPIRE, AFTER_EXPIRY, null),

                arguments(CONFIRMED_STATE, START_PAYMENT, CREATED, null),
                arguments(CONFIRMED_STATE, CONFIRM, CREATED, null),
                arguments(CONFIRMED_STATE, CANCEL, CREATED, CANCELLED),
                arguments(CONFIRMED_STATE, EXPIRE, AFTER_EXPIRY, null),

                arguments(CANCELLED_STATE, START_PAYMENT, CREATED, null),
                arguments(CANCELLED_STATE, CONFIRM, CREATED, null),
                arguments(CANCELLED_STATE, CANCEL, CREATED, null),
                arguments(CANCELLED_STATE, EXPIRE, AFTER_EXPIRY, null),

                arguments(EXPIRED_STATE, START_PAYMENT, CREATED, null),
                arguments(EXPIRED_STATE, CONFIRM, CREATED, null),
                arguments(EXPIRED_STATE, CANCEL, CREATED, null),
                arguments(EXPIRED_STATE, EXPIRE, AFTER_EXPIRY, null));
    }

    @ParameterizedTest(name = "{0} --{1}--> {3}")
    @MethodSource("transitions")
    void transitionMatrix(BookingState from, BookingAction action, Instant now, BookingStatus expected) {
        if (expected == null) {
            assertThatThrownBy(() -> perform(from, action, now))
                    .isInstanceOfSatisfying(IllegalBookingTransitionException.class, ex -> {
                        assertThat(ex.getFrom()).isEqualTo(from.status());
                        assertThat(ex.getAction()).isEqualTo(action);
                    });
        } else {
            assertThat(perform(from, action, now).status()).isEqualTo(expected);
        }
    }

    @Test
    void cannotStartPaymentAfterHoldExpired() {
        assertThatThrownBy(() -> PENDING.startPayment("pay-1", EXPIRES))
                .isInstanceOf(IllegalBookingTransitionException.class)
                .hasMessageContaining("hold expired");
    }

    @Test
    void cannotExpireBeforeHoldEnds() {
        assertThatThrownBy(() -> PENDING.expire(EXPIRES.minusSeconds(1)))
                .isInstanceOf(IllegalBookingTransitionException.class)
                .hasMessageContaining("valid until");
    }

    @Test
    void transitionsCarryStateData() {
        BookingState awaiting = PENDING.startPayment("pay-42", CREATED);
        BookingState confirmed = awaiting.confirm(CREATED.plusSeconds(5));
        BookingState cancelled = confirmed.cancel(CancelReason.PAYMENT_FAILED, CREATED.plusSeconds(9));

        assertThat(awaiting).isEqualTo(new AwaitingPayment("pay-42"));
        assertThat(confirmed).isEqualTo(new Confirmed("pay-42", CREATED.plusSeconds(5)));
        assertThat(cancelled).isEqualTo(new Cancelled(CancelReason.PAYMENT_FAILED, CREATED.plusSeconds(9)));
    }

    @Test
    void statesRejectMissingData() {
        assertThatThrownBy(() -> new Pending(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AwaitingPayment(null)).isInstanceOf(NullPointerException.class);
    }

    private static BookingState perform(BookingState state, BookingAction action, Instant now) {
        return switch (action) {
            case START_PAYMENT -> state.startPayment("pay-1", now);
            case CONFIRM -> state.confirm(now);
            case CANCEL -> state.cancel(CancelReason.USER_REQUEST, now);
            case EXPIRE -> state.expire(now);
        };
    }
}
