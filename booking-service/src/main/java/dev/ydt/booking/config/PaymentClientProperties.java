package dev.ydt.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Вызов payment-service.
 *
 * @param url                  адрес payment-service
 * @param timeout              таймаут соединения и ответа одной попытки
 * @param maxAttempts          попыток всего, считая первую; повтор безопасен, платёж идемпотентен по paymentId
 * @param retryWait            пауза между попытками
 * @param failureRateThreshold доля сбоев в процентах среди последних вызовов, с которой circuit breaker открывается
 * @param openStateWait        сколько circuit breaker открыт, прежде чем пропустить пробные вызовы
 * @param maxConcurrentCalls   bulkhead: сколько вызовов платёжки одновременно, остальные сразу получают отказ
 */
@ConfigurationProperties("payment")
public record PaymentClientProperties(
        String url,
        Duration timeout,
        int maxAttempts,
        Duration retryWait,
        float failureRateThreshold,
        Duration openStateWait,
        int maxConcurrentCalls
) {
}
