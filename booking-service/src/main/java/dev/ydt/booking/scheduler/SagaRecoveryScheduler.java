package dev.ydt.booking.scheduler;

import dev.ydt.booking.service.PaymentSagaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

// Доводит саги оплаты: брошенные на авторизации (сервис упал) и с невыполненным возвратом денег.
@Slf4j
@Component
public class SagaRecoveryScheduler {

    private final PaymentSagaService sagaService;
    private final Duration authorizingTimeout;
    private final Duration refundRetryDelay;

    public SagaRecoveryScheduler(PaymentSagaService sagaService,
                                 @Value("${booking.saga.authorizing-timeout:1m}") Duration authorizingTimeout,
                                 @Value("${booking.saga.refund-retry-delay:10s}") Duration refundRetryDelay) {
        this.sagaService = sagaService;
        this.authorizingTimeout = authorizingTimeout;
        this.refundRetryDelay = refundRetryDelay;
    }

    @Scheduled(fixedDelayString = "${booking.saga.recovery-interval:5s}")
    public void recover() {
        int recovered = sagaService.recover(authorizingTimeout, refundRetryDelay);
        if (recovered > 0) {
            log.info("Recovered {} payment sagas", recovered);
        }
    }
}
