package dev.ydt.booking.entity;

import dev.ydt.booking.dto.event.CatalogEvent;
import dev.ydt.booking.entity.enums.BookingStatus;
import dev.ydt.booking.entity.enums.CancelReason;
import dev.ydt.booking.entity.enums.EventStatus;
import dev.ydt.booking.entity.enums.PricingType;
import dev.ydt.booking.entity.enums.SeatStatus;
import dev.ydt.booking.entity.state.BookingState;
import dev.ydt.booking.exception.IllegalBookingTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static dev.ydt.booking.support.TestEntities.event;
import static dev.ydt.booking.support.TestEntities.seat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
    private static final Duration HOLD_TTL = Duration.ofMinutes(10);

    private CatalogEvent event;
    private Seat seat1;
    private Seat seat2;

    @BeforeEach
    void setUp() {
        event = event(1L, EventStatus.ON_SALE, null);
        seat1 = seat(1L, event, "10000", SeatStatus.HELD);
        seat2 = seat(2L, event, "15000", SeatStatus.HELD);
    }

    @Test
    void createAttachesHeldSeatsAndSumsPrice() {
        Booking booking = newBooking();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.getState()).isEqualTo(new BookingState.Pending(NOW.plus(HOLD_TTL)));
        assertThat(booking.getTotalPrice()).isEqualByComparingTo("25000");
        assertThat(booking.getSeats()).extracting(BookingSeat::getPrice)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("10000"), new BigDecimal("15000"));
        assertThat(List.of(seat1, seat2)).extracting(Seat::getStatus).containsOnly(SeatStatus.HELD);
    }

    @Test
    void createRejectsSeatThatIsNotHeld() {
        Seat notHeld = seat(3L, event, "5000", SeatStatus.AVAILABLE);

        assertThatThrownBy(() -> Booking.create(event.id(), "user-1", List.of(notHeld), PricingType.STANDARD,
                Map.of(3L, new BigDecimal("5000")), NOW, HOLD_TTL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be held");
    }

    @Test
    void paidBookingBooksSeats() {
        Booking booking = newBooking();

        booking.startPayment("pay-1", NOW.plusSeconds(30));
        booking.confirm(NOW.plusSeconds(60));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getPaymentId()).isEqualTo("pay-1");
        assertThat(booking.getConfirmedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(List.of(seat1, seat2)).extracting(Seat::getStatus).containsOnly(SeatStatus.BOOKED);
    }

    @Test
    void cancelReleasesSeats() {
        Booking booking = newBooking();

        booking.cancel(CancelReason.USER_REQUEST, NOW.plusSeconds(30));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getCancelReason()).isEqualTo(CancelReason.USER_REQUEST);
        assertThat(booking.getClosedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(List.of(seat1, seat2)).extracting(Seat::getStatus).containsOnly(SeatStatus.AVAILABLE);
    }

    @Test
    void refundOfConfirmedBookingReleasesBookedSeats() {
        Booking booking = newBooking();
        booking.startPayment("pay-1", NOW);
        booking.confirm(NOW);

        booking.cancel(CancelReason.USER_REQUEST, NOW.plusSeconds(120));

        assertThat(List.of(seat1, seat2)).extracting(Seat::getStatus).containsOnly(SeatStatus.AVAILABLE);
    }

    @Test
    void expireReleasesSeats() {
        Booking booking = newBooking();

        booking.expire(NOW.plus(HOLD_TTL));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(List.of(seat1, seat2)).extracting(Seat::getStatus).containsOnly(SeatStatus.AVAILABLE);
    }

    @Test
    void illegalTransitionDoesNotChangeSeats() {
        Booking booking = newBooking();
        booking.cancel(CancelReason.USER_REQUEST, NOW);

        assertThatThrownBy(() -> booking.confirm(NOW)).isInstanceOf(IllegalBookingTransitionException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(List.of(seat1, seat2)).extracting(Seat::getStatus).containsOnly(SeatStatus.AVAILABLE);
    }

    @Test
    void stateIsRestoredFromColumns() {
        Booking booking = newBooking();
        booking.startPayment("pay-7", NOW);

        assertThat(booking.getState()).isEqualTo(new BookingState.AwaitingPayment("pay-7"));
    }

    private Booking newBooking() {
        return Booking.create(event.id(), "user-1", List.of(seat1, seat2), PricingType.STANDARD,
                Map.of(1L, new BigDecimal("10000"), 2L, new BigDecimal("15000")), NOW, HOLD_TTL);
    }
}
