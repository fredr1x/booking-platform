package dev.ydt.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Чей это запрос для rate limiter'а. Бакеты в Redis общие для всех инстансов gateway.
 * <p>
 * Ключи двух лимитов разные: RedisRateLimiter хранит бакет по ключу, а не по маршруту, и с одинаковым ключом
 * лимит на запись и лимит на чтение тратили бы один бакет.
 */
// shortcut: X-User-Id не проверяется (аутентификации нет), и клиент может обойти лимит, меняя id;
// с JWT ключом станет subject токена
@Configuration
public class RateLimitConfig {

    public static final String USER_HEADER = "X-User-Id";

    // длинный или странный id не станет ключом в Redis: такой запрос считается по IP
    private static final Pattern VALID_USER = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
        return exchange -> Mono.just("user:" + clientOf(exchange));
    }

    @Bean
    public KeyResolver bookingWriteKeyResolver() {
        return exchange -> Mono.just("write:" + clientOf(exchange));
    }

    private static String clientOf(ServerWebExchange exchange) {
        String user = exchange.getRequest().getHeaders().getFirst(USER_HEADER);
        if (user != null && VALID_USER.matcher(user).matches()) {
            return user;
        }
        return "ip:" + Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                .map(InetSocketAddress::getHostString)
                .orElse("unknown");
    }
}
