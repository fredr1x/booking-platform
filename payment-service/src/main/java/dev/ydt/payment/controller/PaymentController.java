package dev.ydt.payment.controller;

import dev.ydt.booking.contracts.payment.AuthorizePaymentRequest;
import dev.ydt.booking.contracts.payment.PaymentResult;
import dev.ydt.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Внутренний API: gateway его наружу не открывает, вызывает только booking-service.
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public PaymentResult authorize(@RequestHeader("Idempotency-Key") String paymentId,
                                   @RequestBody AuthorizePaymentRequest request) {
        return paymentService.authorize(paymentId, request);
    }

    @PostMapping("/{paymentId}/refund")
    public PaymentResult refund(@PathVariable String paymentId) {
        return paymentService.refund(paymentId);
    }
}
