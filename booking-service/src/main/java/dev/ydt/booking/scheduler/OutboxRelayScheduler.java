package dev.ydt.booking.scheduler;

import dev.ydt.booking.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Опрос таблицы, а не CDC (Debezium): проще и без лишнего контейнера, цена — задержка до relay-interval.
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayScheduler {

    private final OutboxService outboxService;

    @Scheduled(fixedDelayString = "${booking.outbox.relay-interval:200ms}")
    public void relay() {
        try {
            // полная пачка значит, что в таблице есть ещё: отправляем без паузы до конца
            while (outboxService.relayBatch() == OutboxService.BATCH_SIZE) {
                log.debug("Outbox batch relayed, continuing");
            }
        } catch (RuntimeException e) {
            log.warn("Outbox relay failed, will retry: {}", e.getMessage());
        }
    }
}
