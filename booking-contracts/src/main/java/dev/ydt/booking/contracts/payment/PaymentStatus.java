package dev.ydt.booking.contracts.payment;

/** Итог платежа. VOIDED: платёж отменили раньше, чем он прошёл, и повторный authorize его уже не проведёт. */
public enum PaymentStatus {
    SUCCEEDED,
    FAILED,
    REFUNDED,
    VOIDED
}
