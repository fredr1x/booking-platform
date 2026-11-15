package dev.ydt.booking.contracts.payment;

/** Ответ payment-service на authorize и refund. Повтор с тем же paymentId возвращает тот же результат. */
public record PaymentResult(
        String paymentId,
        PaymentStatus status,
        String failureReason
) {

    public boolean succeeded() {
        return status == PaymentStatus.SUCCEEDED;
    }
}
