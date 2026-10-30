package dev.ydt.notification.service;

import dev.ydt.booking.contracts.BookingEvent;
import dev.ydt.booking.contracts.BookingEvent.BookingCancelled;
import dev.ydt.booking.contracts.BookingEvent.BookingConfirmed;
import dev.ydt.booking.contracts.BookingEvent.BookingCreated;
import dev.ydt.booking.contracts.BookingEvent.BookingExpired;
import dev.ydt.booking.contracts.BookingEvent.PaymentRequested;
import dev.ydt.notification.entity.Notification;
import dev.ydt.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * «Отправка» уведомления — запись в лог. Порядок: сначала документ в MongoDB, потом отправка.
 * Упали между ними — уведомление потеряно (at-most-once), зато пользователь никогда не получит его дважды.
 * Для письма о покупке билета это обратный выбор тому, что нужен для списания денег.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /** @return {@code true}, если уведомление отправлено сейчас, {@code false} для дубля или служебного события */
    public boolean handle(BookingEvent event) {
        String text = textFor(event);
        if (text == null) {
            return false;
        }
        try {
            notificationRepository.insert(new Notification(event.messageId().toString(), event.bookingId(),
                    event.userId(), event.getClass().getSimpleName(), text, event.occurredAt()));
        } catch (DuplicateKeyException e) {
            log.info("Notification for message {} already sent, skipping", event.messageId());
            return false;
        }
        log.info("Notify {}: {}", event.userId(), text);
        return true;
    }

    private static String textFor(BookingEvent event) {
        return switch (event) {
            case BookingCreated created -> "Бронь #%d создана на %s ₸. Оплатите до %s"
                    .formatted(created.bookingId(), created.totalPrice(), created.expiresAt());
            case BookingConfirmed confirmed -> "Бронь #%d оплачена, билеты ваши".formatted(confirmed.bookingId());
            case BookingCancelled cancelled -> "Бронь #%d отменена: %s"
                    .formatted(cancelled.bookingId(), cancelled.reason());
            case BookingExpired expired -> "Бронь #%d истекла: оплата не поступила вовремя"
                    .formatted(expired.bookingId());
            // служебное событие для платёжки, пользователю писать не о чем
            case PaymentRequested ignored -> null;
        };
    }
}
