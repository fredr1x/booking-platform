package dev.ydt.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Поведение заглушки платёжки.
 *
 * @param delay       сколько «банк» думает над платежом
 * @param failureRate доля отказов от 0 до 1: 0.1 значит каждый десятый платёж отклонён
 */
@ConfigurationProperties("payment")
public record PaymentProperties(
        Duration delay,
        double failureRate
) {
}
