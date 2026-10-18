package dev.ydt.booking.service;

import dev.ydt.booking.config.BookingProperties;
import dev.ydt.booking.dto.booking.BookingResponse;
import dev.ydt.booking.dto.booking.CreateBookingRequest;
import dev.ydt.booking.entity.Booking;
import dev.ydt.booking.entity.Event;
import dev.ydt.booking.entity.Seat;
import dev.ydt.booking.entity.enums.BookingStatus;
import dev.ydt.booking.entity.enums.CancelReason;
import dev.ydt.booking.entity.enums.EventStatus;
import dev.ydt.booking.exception.BookingNotFoundException;
import dev.ydt.booking.exception.EventNotFoundException;
import dev.ydt.booking.exception.EventNotOnSaleException;
import dev.ydt.booking.exception.InvalidSeatSelectionException;
import dev.ydt.booking.mapper.BookingMapper;
import dev.ydt.booking.repository.BookingRepository;
import dev.ydt.booking.repository.EventRepository;
import dev.ydt.booking.repository.SeatRepository;
import dev.ydt.booking.service.pricing.PriceQuote;
import dev.ydt.booking.service.pricing.PricingService;
import dev.ydt.booking.service.locking.SeatHoldStrategy;
import dev.ydt.booking.service.locking.SeatLockingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional
@RequiredArgsConstructor
public class BookingService {

    private static final int EXPIRATION_BATCH_SIZE = 500;

    private final BookingRepository bookingRepository;
    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final PricingService pricingService;
    private final BookingMapper bookingMapper;
    private final BookingProperties properties;
    private final Clock clock;
    private final List<SeatHoldStrategy> seatHoldStrategies;

    public BookingResponse create(String userId, CreateBookingRequest request) {
        return create(userId, request, properties.seatLocking());
    }

    /** Явный выбор стратегии нужен тесту на гонку, который сравнивает все способы на одной базе. */
    public BookingResponse create(String userId, CreateBookingRequest request, SeatLockingMode mode) {
        Instant now = clock.instant();
        Event event = eventRepository.findById(request.eventId())
                .orElseThrow(() -> new EventNotFoundException(request.eventId()));
        if (event.getStatus() != EventStatus.ON_SALE) {
            throw new EventNotOnSaleException(event.getId(), event.getStatus());
        }

        // Быстрый отказ по чтению без блокировок. Защиты от гонки он не даёт, её даёт стратегия ниже.
        List<Seat> seats = loadSeats(event.getId(), request.seatIds());
        SeatHoldStrategy.requireAvailable(seats);
        strategy(mode).hold(seats, now);

        PriceQuote quote = pricingService.quote(event, seats, now);
        Booking booking = Booking.create(event, userId, seats, quote.type(), quote.seatPrices(), now,
                properties.holdTtl());
        return bookingMapper.toResponse(bookingRepository.save(booking));
    }

    @Transactional(readOnly = true)
    public BookingResponse get(Long id, String userId) {
        return bookingMapper.toResponse(getOwned(id, userId));
    }

    public BookingResponse cancel(Long id, String userId) {
        Booking booking = getOwned(id, userId);
        booking.cancel(CancelReason.USER_REQUEST, clock.instant());
        return bookingMapper.toResponse(booking);
    }

    /** Переводит просроченные PENDING-брони в EXPIRED и освобождает места. Возвращает число обработанных. */
    public int expireOverdue() {
        Instant now = clock.instant();
        List<Booking> overdue = bookingRepository.findByStatusAndExpiresAtBefore(
                BookingStatus.PENDING, now, Limit.of(EXPIRATION_BATCH_SIZE));
        overdue.forEach(booking -> booking.expire(now));
        return overdue.size();
    }

    private SeatHoldStrategy strategy(SeatLockingMode mode) {
        return seatHoldStrategies.stream()
                .filter(strategy -> strategy.mode() == mode)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No seat hold strategy for " + mode));
    }

    private List<Seat> loadSeats(Long eventId, List<Long> seatIds) {
        Set<Long> uniqueIds = new LinkedHashSet<>(seatIds);
        if (uniqueIds.size() != seatIds.size()) {
            throw new InvalidSeatSelectionException("Seat ids must be unique");
        }
        List<Seat> seats = seatRepository.findByEventIdAndIdIn(eventId, uniqueIds);
        if (seats.size() != uniqueIds.size()) {
            seats.forEach(seat -> uniqueIds.remove(seat.getId()));
            throw new InvalidSeatSelectionException("Seats " + uniqueIds + " do not belong to event " + eventId);
        }
        return seats;
    }

    // Чужая бронь отдаёт 404, а не 403: не раскрываем, что бронь с таким id существует.
    private Booking getOwned(Long id, String userId) {
        return bookingRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BookingNotFoundException(id));
    }
}
