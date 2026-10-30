package dev.ydt.payment.service;

import dev.ydt.booking.contracts.BookingEvent.PaymentRequested;
import dev.ydt.booking.contracts.PaymentEvent;
import dev.ydt.booking.contracts.PaymentEvent.PaymentFailed;
import dev.ydt.booking.contracts.Topics;
import dev.ydt.payment.config.PaymentProperties;
import dev.ydt.payment.entity.Payment;
import dev.ydt.payment.entity.enums.PaymentStatus;
import dev.ydt.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    @Mock
    PaymentRepository paymentRepository;
    @Mock
    KafkaTemplate<String, String> kafkaTemplate;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void failureRateOfOneDeclinesEveryPayment() {
        var service = new PaymentService(paymentRepository, kafkaTemplate, jsonMapper,
                new PaymentProperties(Duration.ZERO, 1.0), Clock.fixed(NOW, ZoneOffset.UTC));
        var request = new PaymentRequested(UUID.randomUUID(), 7L, "alice", "pay-7", new BigDecimal("100"), NOW);
        when(paymentRepository.findByPaymentId("pay-7")).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        service.process(request);

        var saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);
        var json = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(Topics.PAYMENT_EVENTS), eq("7"), json.capture());
        assertThat(jsonMapper.readValue(json.getValue(), PaymentEvent.class))
                .isInstanceOfSatisfying(PaymentFailed.class, e -> assertThat(e.reason()).isEqualTo("Card declined"));
    }
}
