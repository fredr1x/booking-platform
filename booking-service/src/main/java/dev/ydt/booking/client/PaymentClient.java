package dev.ydt.booking.client;

import dev.ydt.booking.config.PaymentClientProperties;
import dev.ydt.booking.contracts.payment.AuthorizePaymentRequest;
import dev.ydt.booking.contracts.payment.PaymentResult;
import dev.ydt.booking.contracts.web.RequestIdFilter;
import dev.ydt.booking.exception.PaymentUnavailableException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Синхронный вызов payment-service. Обёртки снаружи внутрь: retry → circuit breaker → bulkhead → HTTP.
 * <ul>
 *     <li>Таймаут на каждую попытку: зависшая платёжка не держит поток брони дольше {@code payment.timeout}.</li>
 *     <li>Retry только на таймаут, обрыв и 5xx. Безопасен, потому что payment-service идемпотентен по paymentId
 *     (заголовок Idempotency-Key): повтор после таймаута вернёт тот же платёж, а не спишет деньги второй раз.</li>
 *     <li>Circuit breaker считает каждую попытку. Половина последних вызовов упала — следующие сразу получают
 *     отказ, не тратя поток и таймаут, а платёжка получает паузу на восстановление.</li>
 *     <li>Bulkhead: не больше {@code payment.max-concurrent-calls} вызовов одновременно, чтобы медленная платёжка
 *     не забрала все потоки и соединения сервиса.</li>
 * </ul>
 */
@Slf4j
@Component
public class PaymentClient {

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Bulkhead bulkhead;
    private final Duration openStateWait;

    public PaymentClient(PaymentClientProperties properties) {
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
        requestFactory.setReadTimeout(properties.timeout());
        this.restClient = RestClient.builder().baseUrl(properties.url()).requestFactory(requestFactory)
                .requestInterceptor(RequestIdFilter.propagate()).build();
        this.openStateWait = properties.openStateWait();
        this.circuitBreaker = CircuitBreaker.of("payment", CircuitBreakerConfig.custom()
                // решение по последним 10 вызовам, но не раньше 5: один сбой на холодном старте не откроет breaker
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.openStateWait())
                .permittedNumberOfCallsInHalfOpenState(2)
                // отказ bulkhead — наша перегрузка, а не сбой платёжки
                .ignoreExceptions(BulkheadFullException.class)
                .build());
        this.retry = Retry.of("payment", RetryConfig.custom()
                .maxAttempts(properties.maxAttempts())
                .waitDuration(properties.retryWait())
                .retryOnException(e -> e instanceof ResourceAccessException || e instanceof HttpServerErrorException)
                .build());
        this.bulkhead = Bulkhead.of("payment", BulkheadConfig.custom()
                .maxConcurrentCalls(properties.maxConcurrentCalls())
                .maxWaitDuration(Duration.ZERO)
                .build());
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("Payment circuit breaker: {}", event.getStateTransition()));
    }

    public PaymentResult authorize(String paymentId, Long bookingId, String userId, BigDecimal amount) {
        return call(() -> restClient.post()
                .uri("/api/v1/payments")
                .header("Idempotency-Key", paymentId)
                .body(new AuthorizePaymentRequest(bookingId, userId, amount))
                .retrieve()
                .body(PaymentResult.class));
    }

    public PaymentResult refund(String paymentId) {
        return call(() -> restClient.post()
                .uri("/api/v1/payments/{id}/refund", paymentId)
                .retrieve()
                .body(PaymentResult.class));
    }

    /** Breaker открыт: оплату не начинаем, клиенту сразу 503. */
    public void requireAvailable() {
        if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            throw new PaymentUnavailableException(openStateWait, null);
        }
    }

    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    private PaymentResult call(Supplier<PaymentResult> http) {
        Supplier<PaymentResult> decorated = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker,
                        Bulkhead.decorateSupplier(bulkhead, http)));
        try {
            return decorated.get();
        } catch (CallNotPermittedException | BulkheadFullException | RestClientException e) {
            throw new PaymentUnavailableException(openStateWait, e);
        }
    }
}
