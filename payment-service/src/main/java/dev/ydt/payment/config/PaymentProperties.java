package dev.ydt.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Поведение заглушки платёжки.
 *
 * @param delay       сколько «банк» думает над платежом
 * @param failureRate доля отказов банка от 0 до 1: 0.1 значит каждый десятый платёж отклонён (ответ 200, FAILED)
 * @param errorRate   доля технических сбоев от 0 до 1: ответ 500, платёж не создаётся (для chaos-теста)
 */
@ConfigurationProperties("payment")
public record PaymentProperties(
        Duration delay,
        double failureRate,
        double errorRate
) {
}
