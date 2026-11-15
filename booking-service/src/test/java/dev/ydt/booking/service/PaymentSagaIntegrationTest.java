package dev.ydt.booking.service;

import dev.ydt.booking.client.PaymentClient;
import dev.ydt.booking.dto.booking.BookingResponse;
import dev.ydt.booking.dto.booking.CreateBookingRequest;
import dev.ydt.booking.entity.enums.BookingStatus;
import dev.ydt.booking.entity.enums.CancelReason;
import dev.ydt.booking.exception.PaymentUnavailableException;
import dev.ydt.booking.support.AbstractIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Сага оплаты против заглушки платёжки: успех, отказ банка, сбои, открытый circuit breaker, восстановление. */
class PaymentSagaIntegrationTest extends AbstractIntegrationTest {

    private static final String USER = "saga-user";

    @Autowired
    BookingService bookingService;
    @Autowired
    PaymentSagaService sagaService;
    @Autowired
    PaymentClient paymentClient;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void healthyPayment() {
        paymentMode = PaymentMode.APPROVE;
        paymentGate = null;
        paymentClient.circuitBreaker().reset();
    }

    @AfterEach
    void restorePayment() {
        healthyPayment();
    }

    @Test
    void approvedPaymentConfirmsBooking() {
        BookingResponse booking = createBooking();

        BookingResponse paid = sagaService.pay(booking.id(), USER);

        assertThat(paid.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(seatStatuses(booking.id())).containsOnly("BOOKED");
        assertThat(sagaStatus(booking.id())).isEqualTo("COMPLETED");
    }

    @Test
    void declinedPaymentReleasesSeatsWithoutRefund() {
        paymentMode = PaymentMode.DECLINE;
        BookingResponse booking = createBooking();

        BookingResponse paid = sagaService.pay(booking.id(), USER);

        assertThat(paid.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(paid.cancelReason()).isEqualTo(CancelReason.PAYMENT_FAILED);
        assertThat(seatStatuses(booking.id())).containsOnly("AVAILABLE");
        assertThat(sagaStatus(booking.id())).isEqualTo("COMPENSATED");
        assertThat(REFUNDED_PAYMENTS).doesNotContain(paid.paymentId());
    }

    @Test
    void failedPaymentIsCompensatedAndRefundIsRetriedUntilItSucceeds() {
        paymentMode = PaymentMode.ERROR;
        BookingResponse booking = createBooking();

        BookingResponse paid = sagaService.pay(booking.id(), USER);

        assertThat(paid.status()).as("места возвращаются сразу, не дожидаясь платёжки").isEqualTo(BookingStatus.CANCELLED);
        assertThat(paid.cancelReason()).isEqualTo(CancelReason.PAYMENT_UNAVAILABLE);
        assertThat(seatStatuses(booking.id())).containsOnly("AVAILABLE");
        assertThat(sagaStatus(booking.id())).as("возврат не прошёл, ждёт повтора").isEqualTo("COMPENSATING");

        paymentMode = PaymentMode.APPROVE;
        paymentClient.circuitBreaker().reset();
        sagaService.recover(Duration.ofHours(1), Duration.ZERO);

        assertThat(sagaStatus(booking.id())).isEqualTo("COMPENSATED");
        assertThat(REFUNDED_PAYMENTS).contains(paid.paymentId());
    }

    @Test
    void slowPaymentTimesOutAndIsCompensated() {
        paymentMode = PaymentMode.SLOW;
        BookingResponse booking = createBooking();

        long started = System.nanoTime();
        BookingResponse paid = sagaService.pay(booking.id(), USER);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(paid.cancelReason()).isEqualTo(CancelReason.PAYMENT_UNAVAILABLE);
        // две попытки по 300 мс на authorize и столько же на refund, а не секунда на каждый ответ заглушки
        assertThat(millis).isLessThan(2000);
    }

    @Test
    void openCircuitBreakerFailsFastAndLeavesBookingUntouched() {
        paymentMode = PaymentMode.ERROR;
        // каждая сага — две попытки authorize и две refund: окно breaker'а заполняется за пару оплат
        for (int i = 0; i < 3 && paymentClient.circuitBreaker().getState() != CircuitBreaker.State.OPEN; i++) {
            sagaService.pay(createBooking().id(), USER);
        }
        assertThat(paymentClient.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        BookingResponse booking = createBooking();
        int requestsBefore = PAYMENT_REQUESTS.get();
        long started = System.nanoTime();
        assertThatThrownBy(() -> sagaService.pay(booking.id(), USER)).isInstanceOf(PaymentUnavailableException.class);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(millis).as("отказ без похода в платёжку").isLessThan(100);
        assertThat(PAYMENT_REQUESTS.get()).isEqualTo(requestsBefore);
        assertThat(bookingService.get(booking.id(), USER).status())
                .as("бронь не тронута, клиент повторит оплату позже").isEqualTo(BookingStatus.PENDING);

        // платёжка ожила, пауза breaker'а прошла: пробные вызовы проходят и закрывают его
        paymentMode = PaymentMode.APPROVE;
        paymentClient.circuitBreaker().transitionToHalfOpenState();
        assertThat(sagaService.pay(booking.id(), USER).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(sagaService.pay(createBooking().id(), USER).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(paymentClient.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void userCancelDuringPaymentGetsMoneyBack() throws Exception {
        BookingResponse booking = createBooking();
        paymentGate = new CountDownLatch(1);
        int requestsBefore = PAYMENT_REQUESTS.get();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var paying = executor.submit(() -> sagaService.pay(booking.id(), USER));
            await().atMost(Duration.ofSeconds(2)).until(() -> PAYMENT_REQUESTS.get() > requestsBefore);
            bookingService.cancel(booking.id(), USER);
            paymentGate.countDown();

            BookingResponse result = paying.get();
            assertThat(result.status()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(result.cancelReason()).isEqualTo(CancelReason.USER_REQUEST);
            assertThat(sagaStatus(booking.id())).isEqualTo("COMPENSATED");
            assertThat(REFUNDED_PAYMENTS).as("деньги списались после отмены, их вернули").contains(result.paymentId());
        }
    }

    @Test
    void sagaAbandonedByCrashIsFinishedByRecovery() {
        BookingResponse booking = createBooking();
        // сервис упал после записи шага, но до ответа платёжки
        jdbc.update("UPDATE bookings SET status = 'AWAITING_PAYMENT', payment_id = 'crash-' || id WHERE id = ?",
                booking.id());
        jdbc.update("""
                INSERT INTO saga_state (booking_id, payment_id, status, updated_at)
                VALUES (?, 'crash-' || ?, 'AUTHORIZING', now() - interval '5 minutes')
                """, booking.id(), booking.id());

        assertThat(sagaService.recover(Duration.ofMinutes(1), Duration.ofHours(1))).isGreaterThanOrEqualTo(1);

        assertThat(bookingService.get(booking.id(), USER).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(sagaStatus(booking.id())).isEqualTo("COMPLETED");
    }

    private BookingResponse createBooking() {
        Long seatId = jdbc.queryForObject(
                "SELECT id FROM seats WHERE event_id = 1 AND status = 'AVAILABLE' ORDER BY id DESC LIMIT 1", Long.class);
        return bookingService.create(USER, new CreateBookingRequest(1L, List.of(seatId)));
    }

    private String sagaStatus(Long bookingId) {
        return jdbc.queryForObject("SELECT status FROM saga_state WHERE booking_id = ?", String.class, bookingId);
    }

    private List<String> seatStatuses(Long bookingId) {
        return jdbc.queryForList("""
                SELECT s.status FROM seats s JOIN booking_seats bs ON bs.seat_id = s.id WHERE bs.booking_id = ?
                """, String.class, bookingId);
    }
}
