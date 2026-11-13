package dev.ydt.booking.controller;

import dev.ydt.booking.exception.BookingOverloadedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.Semaphore;

/**
 * Bulkhead на создание брони. Виртуальные потоки принимают сколько угодно запросов, и без лимита они копились бы
 * в очереди к пулу соединений, пока не упрутся в таймаут. Лишний запрос сразу получает 503 и Retry-After:
 * быстрый отказ лучше медленного, и остальным достаётся вся пропускная способность.
 */
@Component
public class BookingBulkheadInterceptor implements HandlerInterceptor {

    private final Semaphore permits;
    private final Counter rejected;

    public BookingBulkheadInterceptor(@Value("${booking.max-concurrent-requests}") int maxConcurrentRequests,
                                      MeterRegistry meterRegistry) {
        this.permits = new Semaphore(maxConcurrentRequests);
        this.rejected = Counter.builder("booking.bulkhead.rejected")
                .description("Booking requests rejected with 503 because too many were in progress")
                .register(meterRegistry);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        if (!permits.tryAcquire()) {
            rejected.increment();
            throw new BookingOverloadedException();
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // вызывается, только если preHandle вернул true: отвергнутый запрос разрешение не вернёт
        if (HttpMethod.POST.matches(request.getMethod())) {
            permits.release();
        }
    }
}
