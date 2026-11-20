package dev.ydt.booking.contracts.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Кладёт X-Request-Id из gateway в MDC: в JSON-логе он становится полем {@code requestId}, и по нему одна бронь
 * ищется в логах всех сервисов. Подключается в сервис через {@code @Import(RequestIdFilter.class)}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    // сервисы доступны и мимо gateway, поэтому чужой id проверяем так же, как gateway: он попадёт в логи
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    /** Для RestClient: исходящий вызов несёт тот же request id, что и входящий запрос. */
    public static ClientHttpRequestInterceptor propagate() {
        return (request, body, execution) -> {
            String requestId = MDC.get(MDC_KEY);
            if (requestId != null) {
                request.getHeaders().set(HEADER, requestId);
            }
            return execution.execute(request, body);
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = incoming != null && VALID_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        // в ответ id не пишем: его возвращает клиенту gateway, иначе заголовок в ответе задвоится
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
