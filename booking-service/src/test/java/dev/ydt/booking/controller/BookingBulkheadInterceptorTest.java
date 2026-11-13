package dev.ydt.booking.controller;

import dev.ydt.booking.exception.BookingOverloadedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingBulkheadInterceptorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BookingBulkheadInterceptor bulkhead = new BookingBulkheadInterceptor(1, registry);
    private final MockHttpServletRequest post = new MockHttpServletRequest("POST", "/api/v1/bookings");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void rejectsOverLimitAndAdmitsAgainAfterCompletion() {
        assertThat(bulkhead.preHandle(post, response, this)).isTrue();

        assertThatThrownBy(() -> bulkhead.preHandle(post, response, this))
                .isInstanceOf(BookingOverloadedException.class);
        assertThat(registry.counter("booking.bulkhead.rejected").count()).isEqualTo(1);

        bulkhead.afterCompletion(post, response, this, null);
        assertThat(bulkhead.preHandle(post, response, this)).isTrue();
    }

    @Test
    void getDoesNotTakePermit() {
        var get = new MockHttpServletRequest("GET", "/api/v1/bookings");

        assertThat(bulkhead.preHandle(get, response, this)).isTrue();
        bulkhead.afterCompletion(get, response, this, null);

        assertThat(bulkhead.preHandle(post, response, this)).isTrue();
        assertThatThrownBy(() -> bulkhead.preHandle(post, response, this))
                .isInstanceOf(BookingOverloadedException.class);
    }
}
