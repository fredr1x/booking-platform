package dev.ydt.booking.service;

import dev.ydt.booking.config.BookingProperties;
import dev.ydt.booking.dto.booking.BookingResponse;
import dev.ydt.booking.dto.booking.CreateBookingRequest;
import dev.ydt.booking.entity.Booking;
import dev.ydt.booking.entity.Event;
import dev.ydt.booking.entity.Seat;
import dev.ydt.booking.entity.enums.BookingStatus;
import dev.ydt.booking.entity.enums.EventStatus;
import dev.ydt.booking.entity.enums.PricingType;
import dev.ydt.booking.entity.enums.SeatStatus;
import dev.ydt.booking.exception.BookingNotFoundException;
import dev.ydt.booking.exception.EventNotOnSaleException;
import dev.ydt.booking.exception.InvalidSeatSelectionException;
import dev.ydt.booking.exception.SeatUnavailableException;
import dev.ydt.booking.mapper.BookingMapper;
import dev.ydt.booking.repository.BookingRepository;
import dev.ydt.booking.repository.EventRepository;
import dev.ydt.booking.repository.IdempotencyKeyRepository;
import dev.ydt.booking.repository.SeatRepository;
import dev.ydt.booking.service.locking.SeatHoldStrategy;
import dev.ydt.booking.service.locking.SeatLockingMode;
import dev.ydt.booking.service.pricing.PriceQuote;
import dev.ydt.booking.service.pricing.PricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.ydt.booking.support.TestEntities.event;
import static dev.ydt.booking.support.TestEntities.seat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
    private static final String USER = "user-1";

    @Mock
    BookingRepository bookingRepository;
    @Mock
    EventRepository eventRepository;
    @Mock
    SeatRepository seatRepository;
    @Mock
    PricingService pricingService;
    @Mock
    BookingMapper bookingMapper;
    @Mock
    IdempotencyKeyRepository idempotencyKeyRepository;

    private BookingService bookingService;
    private Event event;
    private Seat seat1;
    private Seat seat2;

    @BeforeEach
    void setUp() {
        BookingProperties properties = new BookingProperties(Duration.ofMinutes(10), SeatLockingMode.ATOMIC, null);
        bookingService = new BookingService(bookingRepository, eventRepository, seatRepository, idempotencyKeyRepository,
                pricingService, bookingMapper, properties, Clock.fixed(NOW, ZoneOffset.UTC), List.of(new InMemoryHold()));
        event = event(1L, EventStatus.ON_SALE, null);
        seat1 = seat(10L, event, "10000");
        seat2 = seat(11L, event, "20000");
    }

    @Test
    void createHoldsSeatsAndSavesPendingBooking() {
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(seatRepository.findByEventIdAndIdIn(eq(1L), any())).thenReturn(List.of(seat1, seat2));
        when(pricingService.quote(event, List.of(seat1, seat2), NOW)).thenReturn(new PriceQuote(PricingType.STANDARD,
                Map.of(10L, new BigDecimal("10000"), 11L, new BigDecimal("20000"))));
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        BookingResponse response = mock(BookingResponse.class);
        when(bookingMapper.toResponse(any())).thenReturn(response);

        BookingResponse result = bookingService.create(USER, new CreateBookingRequest(1L, List.of(10L, 11L)));

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        Booking booking = saved.getValue();
        assertThat(result).isSameAs(response);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.getUserId()).isEqualTo(USER);
        assertThat(booking.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(booking.getTotalPrice()).isEqualByComparingTo("30000");
        assertThat(List.of(seat1, seat2)).extracting(Seat::getStatus).containsOnly(SeatStatus.HELD);
    }

    @Test
    void rejectsEventNotOnSale() {
        Event draft = event(2L, EventStatus.DRAFT, null);
        when(eventRepository.findById(2L)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> bookingService.create(USER, new CreateBookingRequest(2L, List.of(10L))))
                .isInstanceOf(EventNotOnSaleException.class);
        verifyNoInteractions(seatRepository, bookingRepository);
    }

    @Test
    void rejectsDuplicateSeatIds() {
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> bookingService.create(USER, new CreateBookingRequest(1L, List.of(10L, 10L))))
                .isInstanceOf(InvalidSeatSelectionException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void rejectsSeatsOfAnotherEvent() {
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(seatRepository.findByEventIdAndIdIn(eq(1L), any())).thenReturn(List.of(seat1));

        assertThatThrownBy(() -> bookingService.create(USER, new CreateBookingRequest(1L, List.of(10L, 99L))))
                .isInstanceOf(InvalidSeatSelectionException.class)
                .hasMessageContaining("[99]");
    }

    @Test
    void reportsAllUnavailableSeats() {
        Seat held = seat(12L, event, "10000", SeatStatus.HELD);
        Seat booked = seat(13L, event, "10000", SeatStatus.BOOKED);
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(seatRepository.findByEventIdAndIdIn(eq(1L), any())).thenReturn(List.of(seat1, held, booked));

        assertThatThrownBy(() -> bookingService.create(USER, new CreateBookingRequest(1L, List.of(10L, 12L, 13L))))
                .isInstanceOfSatisfying(SeatUnavailableException.class,
                        ex -> assertThat(ex.getSeatIds()).containsExactly(12L, 13L));
        verify(bookingRepository, never()).save(any());
        verify(pricingService, never()).quote(any(), anyList(), any());
    }

    @Test
    void cancelOfAnotherUsersBookingIsNotFound() {
        when(bookingRepository.findByIdAndUserId(5L, "intruder")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.cancel(5L, "intruder"))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void expireOverdueExpiresPendingBookings() {
        seat1.hold(NOW.minus(Duration.ofMinutes(20)));
        Booking booking = Booking.create(event, USER, List.of(seat1), PricingType.STANDARD,
                Map.of(10L, new BigDecimal("10000")), NOW.minus(Duration.ofMinutes(20)), Duration.ofMinutes(10));
        when(bookingRepository.findByStatusAndExpiresAtBefore(eq(BookingStatus.PENDING), eq(NOW), any()))
                .thenReturn(List.of(booking));

        int expired = bookingService.expireOverdue();

        assertThat(expired).isEqualTo(1);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(seat1.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    // Блокировки проверяет SeatRaceIntegrationTest на настоящей БД, здесь достаточно перевести места в HELD.
    private static class InMemoryHold implements SeatHoldStrategy {
        @Override
        public SeatLockingMode mode() {
            return SeatLockingMode.ATOMIC;
        }

        @Override
        public void hold(List<Seat> seats, Instant now) {
            seats.forEach(seat -> seat.hold(now));
        }
    }
}
