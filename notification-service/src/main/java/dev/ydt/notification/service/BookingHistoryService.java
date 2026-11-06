package dev.ydt.notification.service;

import dev.ydt.booking.contracts.BookingEvent;
import dev.ydt.booking.contracts.BookingEvent.BookingCancelled;
import dev.ydt.booking.contracts.BookingEvent.BookingConfirmed;
import dev.ydt.booking.contracts.BookingEvent.BookingCreated;
import dev.ydt.booking.contracts.BookingEvent.BookingExpired;
import dev.ydt.booking.contracts.BookingEvent.PaymentRequested;
import dev.ydt.notification.dto.booking.BookingHistoryResponse;
import dev.ydt.notification.entity.BookingHistory;
import dev.ydt.notification.entity.enums.BookingStatus;
import dev.ydt.notification.mapper.BookingHistoryMapper;
import dev.ydt.notification.repository.BookingHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Проекция booking.events в историю броней. Обе записи идемпотентны, поэтому дубль события ничего не портит
 * и отдельный учёт обработанных messageId не нужен:
 * <ol>
 *     <li>upsert данных брони: те же значения ставятся повторно;</li>
 *     <li>статус меняется, только если событие новее того, что поставило текущий статус.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class BookingHistoryService {

    private final MongoTemplate mongoTemplate;
    private final BookingHistoryRepository bookingHistoryRepository;
    private final BookingHistoryMapper bookingHistoryMapper;

    public void apply(BookingEvent event) {
        var byId = query(where("_id").is(event.bookingId()));
        mongoTemplate.upsert(byId, details(event).setOnInsert("userId", event.userId()), BookingHistory.class);

        var olderStatus = query(where("_id").is(event.bookingId())
                .orOperator(where("statusAt").exists(false), where("statusAt").lt(event.occurredAt())));
        mongoTemplate.updateFirst(olderStatus,
                new Update().set("status", statusOf(event)).set("statusAt", event.occurredAt()),
                BookingHistory.class);
    }

    public List<BookingHistoryResponse> findByUser(String userId, int page, int size) {
        return bookingHistoryMapper.toResponses(
                bookingHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size)));
    }

    private static Update details(BookingEvent event) {
        return switch (event) {
            case BookingCreated created -> new Update()
                    .set("eventId", created.eventId())
                    .set("totalPrice", created.totalPrice())
                    .set("expiresAt", created.expiresAt())
                    .set("createdAt", created.occurredAt());
            case PaymentRequested requested -> new Update().set("paymentId", requested.paymentId());
            case BookingConfirmed confirmed -> new Update().set("paymentId", confirmed.paymentId());
            case BookingCancelled cancelled -> new Update().set("cancelReason", cancelled.reason());
            case BookingExpired ignored -> new Update();
        };
    }

    private static BookingStatus statusOf(BookingEvent event) {
        return switch (event) {
            case BookingCreated ignored -> BookingStatus.PENDING;
            case PaymentRequested ignored -> BookingStatus.AWAITING_PAYMENT;
            case BookingConfirmed ignored -> BookingStatus.CONFIRMED;
            case BookingCancelled ignored -> BookingStatus.CANCELLED;
            case BookingExpired ignored -> BookingStatus.EXPIRED;
        };
    }
}
