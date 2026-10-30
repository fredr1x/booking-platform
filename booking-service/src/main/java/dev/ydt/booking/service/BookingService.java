package dev.ydt.booking.service;

import dev.ydt.booking.client.CatalogClient;
import dev.ydt.booking.contracts.BookingEvent.BookingCancelled;
import dev.ydt.booking.contracts.BookingEvent.BookingConfirmed;
import dev.ydt.booking.contracts.BookingEvent.BookingCreated;
import dev.ydt.booking.contracts.BookingEvent.BookingExpired;
import dev.ydt.booking.contracts.BookingEvent.PaymentRequested;
import dev.ydt.booking.contracts.PaymentEvent;
import dev.ydt.booking.contracts.PaymentEvent.PaymentFailed;
import dev.ydt.booking.contracts.PaymentEvent.PaymentSucceeded;
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
import dev.ydt.booking.exception.IllegalBookingTransitionException;
import dev.ydt.booking.exception.InvalidSeatSelectionException;
import dev.ydt.booking.mapper.BookingMapper;
import dev.ydt.booking.repository.BookingRepository;
import dev.ydt.booking.repository.IdempotencyKeyRepository;
import dev.ydt.booking.repository.ProcessedMessageRepository;
import dev.ydt.booking.repository.SeatRepository;
import dev.ydt.booking.service.pricing.PriceQuote;
import dev.ydt.booking.service.pricing.PricingService;
import dev.ydt.booking.service.locking.SeatHoldStrategy;
import dev.ydt.booking.service.locking.SeatLockingMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BookingService {

    private static final int EXPIRATION_BATCH_SIZE = 500;
    // как у Stripe: сутки покрывают любые разумные ретраи клиента
    private static final Duration IDEMPOTENCY_KEY_TTL = Duration.ofHours(24);
    // дольше Kafka по умолчанию хранит сообщения 7 дней: старше этого повторная доставка уже не придёт
    private static final Duration PROCESSED_MESSAGE_TTL = Duration.ofDays(8);

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
    private final OutboxService outboxService;
    private final ProcessedMessageRepository processedMessageRepository;

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
        Instant now = clock.instant();
        booking.cancel(CancelReason.USER_REQUEST, now);
        outboxService.publish(new BookingCancelled(UUID.randomUUID(), booking.getId(), userId,
                CancelReason.USER_REQUEST.name(), now));
        return bookingMapper.toResponse(booking);
    }

    /**
     * Запускает оплату: бронь ждёт результат, payment-service получает запрос через outbox.
     * Ответ сразу, без ожидания платёжки: результат придёт событием, клиент увидит его по GET.
     */
    public BookingResponse pay(Long id, String userId) {
        Booking booking = getOwned(id, userId);
        Instant now = clock.instant();
        booking.startPayment(UUID.randomUUID().toString(), now);
        outboxService.publish(new PaymentRequested(UUID.randomUUID(), booking.getId(), userId,
                booking.getPaymentId(), booking.getTotalPrice(), now));
        return bookingMapper.toResponse(booking);
    }

    /**
     * Результат оплаты. Отметка «сообщение обработано» и смена статуса в одной транзакции:
     * повторная доставка того же сообщения увидит отметку и ничего не сделает.
     */
    public void applyPayment(PaymentEvent event) {
        Instant now = clock.instant();
        if (processedMessageRepository.markProcessed(event.messageId(), now) == 0) {
            log.info("Payment event {} already processed, skipping", event.messageId());
            return;
        }
        Booking booking = bookingRepository.findById(event.bookingId())
                .orElseThrow(() -> new BookingNotFoundException(event.bookingId()));
        if (!event.paymentId().equals(booking.getPaymentId())) {
            log.warn("Payment {} does not match booking {} (payment {}), ignoring",
                    event.paymentId(), booking.getId(), booking.getPaymentId());
            return;
        }
        try {
            switch (event) {
                case PaymentSucceeded succeeded -> {
                    booking.confirm(now);
                    outboxService.publish(new BookingConfirmed(UUID.randomUUID(), booking.getId(),
                            booking.getUserId(), succeeded.paymentId(), now));
                }
                case PaymentFailed failed -> {
                    booking.cancel(CancelReason.PAYMENT_FAILED, now);
                    outboxService.publish(new BookingCancelled(UUID.randomUUID(), booking.getId(),
                            booking.getUserId(), CancelReason.PAYMENT_FAILED.name(), now));
                }
            }
        } catch (IllegalBookingTransitionException e) {
            // shortcut: пользователь отменил бронь, пока шла оплата; деньги списаны, нужен возврат — saga в блоке 9
            log.warn("Payment result for booking {} arrived in status {}: {}",
                    booking.getId(), booking.getStatus(), e.getMessage());
        }
    }

    /** Переводит просроченные PENDING-брони в EXPIRED и освобождает места. Возвращает число обработанных. */
    public int expireOverdue() {
        Instant now = clock.instant();
        List<Booking> overdue = bookingRepository.findByStatusAndExpiresAtBefore(
                BookingStatus.PENDING, now, Limit.of(EXPIRATION_BATCH_SIZE));
        overdue.forEach(booking -> {
            booking.expire(now);
            outboxService.publish(new BookingExpired(UUID.randomUUID(), booking.getId(), booking.getUserId(), now));
        });
        return overdue.size();
    }

    public int purgeIdempotencyKeys() {
        return idempotencyKeyRepository.deleteCreatedBefore(clock.instant().minus(IDEMPOTENCY_KEY_TTL));
    }

    public int purgeProcessedMessages() {
        return processedMessageRepository.deleteProcessedBefore(clock.instant().minus(PROCESSED_MESSAGE_TTL));
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
        var booking = bookingRepository.save(Booking.create(event.id(), userId, seats, quote.type(),
                quote.seatPrices(), now, properties.holdTtl()));
        outboxService.publish(new BookingCreated(UUID.randomUUID(), booking.getId(), userId, booking.getEventId(),
                booking.getTotalPrice(), booking.getExpiresAt(), now));
        return bookingMapper.toResponse(booking);
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
