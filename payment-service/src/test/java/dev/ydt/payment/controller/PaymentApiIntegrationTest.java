package dev.ydt.payment.controller;

import dev.ydt.booking.contracts.payment.AuthorizePaymentRequest;
import dev.ydt.payment.config.PaymentProperties;
import dev.ydt.payment.exception.PaymentProviderException;
import dev.ydt.payment.repository.PaymentRepository;
import dev.ydt.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PaymentApiIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    PaymentRepository paymentRepository;

    @Test
    void repeatedAuthorizeReturnsTheSamePaymentAndChargesOnce() throws Exception {
        String paymentId = UUID.randomUUID().toString();

        authorize(paymentId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        authorize(paymentId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));

        assertThat(paymentRepository.findAll()).filteredOn(p -> p.getPaymentId().equals(paymentId)).hasSize(1);
    }

    @Test
    void refundReturnsSucceededPaymentAndIsIdempotent() throws Exception {
        String paymentId = UUID.randomUUID().toString();
        authorize(paymentId).andExpect(jsonPath("$.status").value("SUCCEEDED"));

        refund(paymentId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));
        refund(paymentId).andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void lateAuthorizeAfterRefundDoesNotCharge() throws Exception {
        String paymentId = UUID.randomUUID().toString();

        // booking-service не дождался ответа и отменил платёж, а запрос authorize дошёл позже
        refund(paymentId).andExpect(jsonPath("$.status").value("VOIDED"));
        authorize(paymentId).andExpect(jsonPath("$.status").value("VOIDED"));
    }

    @Test
    void providerErrorSavesNothingSoRetryIsSafe() {
        var failing = new PaymentService(paymentRepository, new PaymentProperties(Duration.ZERO, 0, 1.0),
                Clock.systemUTC());
        String paymentId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> failing.authorize(paymentId, new AuthorizePaymentRequest(1L, "alice", BigDecimal.TEN)))
                .isInstanceOf(PaymentProviderException.class);

        assertThat(paymentRepository.findByPaymentId(paymentId)).isEmpty();
    }

    private ResultActions authorize(String paymentId) throws Exception {
        return mvc.perform(post("/api/v1/payments")
                .header("Idempotency-Key", paymentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"bookingId": 7, "userId": "alice", "amount": 2500.00}
                        """));
    }

    private ResultActions refund(String paymentId) throws Exception {
        return mvc.perform(post("/api/v1/payments/{id}/refund", paymentId));
    }
}
