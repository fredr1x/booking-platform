package dev.ydt.notification.entity.enums;

/** Статусы брони, как их видит read-модель. Свой enum, а не из booking-service: сервисы не делят код домена. */
public enum BookingStatus {
    PENDING,
    AWAITING_PAYMENT,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
