package dev.ydt.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * RequestRateLimiter отвечает 429 с X-RateLimit-* заголовками, но без Retry-After. Добавляем его:
 * клиент узнаёт, когда повторить, и не долбит gateway повторами (как с 503 в booking-service).
 */
@Component
public class RetryAfterFilter implements GlobalFilter, Ordered {

    // бакет пополняется каждую секунду, через секунду в нём уже есть токен
    static final String RETRY_AFTER_SECONDS = "1";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var response = exchange.getResponse();
        response.beforeCommit(() -> {
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                response.getHeaders().set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
