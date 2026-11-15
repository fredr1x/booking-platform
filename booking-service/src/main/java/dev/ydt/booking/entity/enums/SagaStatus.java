package dev.ydt.booking.entity.enums;

/** AUTHORIZING → COMPLETED, или AUTHORIZING → COMPENSATING (бронь отменена, ждём возврат денег) → COMPENSATED. */
public enum SagaStatus {
    AUTHORIZING,
    COMPLETED,
    COMPENSATING,
    COMPENSATED
}
