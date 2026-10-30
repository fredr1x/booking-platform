package dev.ydt.booking.contracts;

/**
 * Имена топиков. Ключ сообщения везде {@code bookingId}: все события одной брони попадают в одну партицию
 * и читаются в том порядке, в каком были записаны.
 */
public final class Topics {

    /** События брони: создана, ждёт оплату, подтверждена, отменена, истекла. Пишет booking-service. */
    public static final String BOOKING_EVENTS = "booking.events";

    /** Результаты оплаты. Пишет payment-service. */
    public static final String PAYMENT_EVENTS = "payment.events";

    /** Партиций на топик: столько consumer'ов одной группы могут читать параллельно. */
    public static final int PARTITIONS = 3;

    private Topics() {
    }
}
