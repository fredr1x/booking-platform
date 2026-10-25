package dev.ydt.booking.service;

import dev.ydt.booking.client.CatalogClient;
import dev.ydt.booking.config.BookingProperties;
import dev.ydt.booking.dto.booking.BookingResponse;
import dev.ydt.booking.dto.booking.CreateBookingRequest;
import dev.ydt.booking.dto.event.CatalogEvent;
import dev.ydt.booking.entity.Booking;
import dev.ydt.booking.entity.IdempotencyKey;
import dev.ydt.booking.entity.Seat;
import dev.ydt.booking.entity.enums.BookingStatus;
import dev.ydt.booking.entity.enums.CancelReason;
import dev.ydt.booking.entity.enums.EventStatus;
import dev.ydt.booking.exception.BookingNotFoundException;
import dev.ydt.booking.exception.EventNotOnSaleException;
import dev.ydt.booking.exception.IdempotencyKeyReusedException;
import dev.ydt.booking.exception.InvalidSeatSelectionException;
import dev.ydt.booking.mapper.BookingMapper;
import dev.ydt.booking.repository.BookingRepository;
import dev.ydt.booking.repository.IdempotencyKeyRepository;
import dev.ydt.booking.repository.SeatRepository;
import dev.ydt.booking.service.pricing.PriceQuote;
import dev.ydt.booking.service.pricing.PricingService;
import dev.ydt.booking.service.locking.SeatHoldStrategy;
import dev.ydt.booking.service.locking.SeatLockingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
public class BookingService {

    private static final int EXPIRATION_BATCH_SIZE = 500;
    // как у Stripe: сутки покрывают любые разумные ретраи клиента
    private static final Duration IDEMPOTENCY_KEY_TTL = Duration.ofHours(24);

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final PricingService pricingService;
    private final BookingMapper bookingMapper;
    private final BookingProperties properties;
    private final Clock clock;
    private final List<SeatHoldStrategy> seatHoldStrategies;
    private final CatalogClient catalogClient;
    private final TransactionTemplate transactionTemplate;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BookingResponse create(String userId, CreateBookingRequest request) {
        return create(userId, request, properties.seatLocking());
    }

    /**
     * Повтор с тем же ключом возвращает уже созданную бронь. Ключ пишется в той же транзакции, что и бронь:
     * если бронь не создалась (место занято), ключ откатится вместе с ней и повтор выполнится заново.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BookingResponse create(String userId, String idempotencyKey, CreateBookingRequest request) {
        if (idempotencyKey == null) {
            return create(userId, request);
        }
        var keyId = new IdempotencyKey.Id(userId, idempotencyKey);
        String requestHash = sha256(request.toString());
        // известный ключ отвечаем без похода в каталог: событие могло уже уйти с продажи, а бронь есть
        BookingResponse replayed = transactionTemplate.execute(status -> idempotencyKeyRepository.findById(keyId)
                .map(saved -> replay(saved, requestHash, userId))
                .orElse(null));
        if (replayed != null) {
            return replayed;
        }

        CatalogEvent event = onSaleEvent(request.eventId());
        return transactionTemplate.execute(status -> {
            if (idempotencyKeyRepository.insertIfAbsent(userId, idempotencyKey, requestHash, clock.instant()) == 0) {
                return replay(idempotencyKeyRepository.findById(keyId).orElseThrow(), requestHash, userId);
            }
            BookingResponse booking = hold(userId, request, properties.seatLocking(), event);
            idempotencyKeyRepository.attachBooking(keyId, booking.id());
            return booking;
        });
    }

    /**
     * Явный выбор стратегии нужен тесту на гонку, который сравнивает все способы на одной базе.
     * <p>
     * Каталог спрашиваем до транзакции: иначе на время HTTP-вызова бронь держала бы соединение
     * из пула на 20, и медленный каталог исчерпал бы пул для всех остальных запросов.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BookingResponse create(String userId, CreateBookingRequest request, SeatLockingMode mode) {
        CatalogEvent event = onSaleEvent(request.eventId());
        return transactionTemplate.execute(status -> hold(userId, request, mode, event));
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

    public int purgeIdempotencyKeys() {
        return idempotencyKeyRepository.deleteCreatedBefore(clock.instant().minus(IDEMPOTENCY_KEY_TTL));
    }

    private CatalogEvent onSaleEvent(Long eventId) {
        CatalogEvent event = catalogClient.getEvent(eventId);
        if (event.status() != EventStatus.ON_SALE) {
            throw new EventNotOnSaleException(event.id(), event.status());
        }
        return event;
    }

    private BookingResponse hold(String userId, CreateBookingRequest request, SeatLockingMode mode,
                                 CatalogEvent event) {
        Instant now = clock.instant();
        // Быстрый отказ по чтению без блокировок. Защиты от гонки он не даёт, её даёт стратегия ниже.
        List<Seat> seats = loadSeats(event.id(), request.seatIds());
        SeatHoldStrategy.requireAvailable(seats);
        strategy(mode).hold(seats, now);

        PriceQuote quote = pricingService.quote(event, seats, now);
        var booking = Booking.create(event.id(), userId, seats, quote.type(), quote.seatPrices(), now,
                properties.holdTtl());
        return bookingMapper.toResponse(bookingRepository.save(booking));
    }

    private BookingResponse replay(IdempotencyKey saved, String requestHash, String userId) {
        if (!saved.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException(saved.getId().getKey());
        }
        return bookingMapper.toResponse(getOwned(saved.getBookingId(), userId));
    }

    private SeatHoldStrategy strategy(SeatLockingMode mode) {
        return seatHoldStrategies.stream()
                .filter(strategy -> strategy.mode() == mode)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No seat hold strategy for " + mode));
    }

    private List<Seat> loadSeats(Long eventId, List<Long> seatIds) {
        var uniqueIds = new LinkedHashSet<>(seatIds);
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

    // toString у record детерминирован и включает все поля, поэтому годится как каноничная форма запроса.
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
