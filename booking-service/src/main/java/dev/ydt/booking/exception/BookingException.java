package dev.ydt.booking.exception;

/**
 * Базовое исключение домена. Sealed: обработчик ошибок перебирает подклассы через switch,
 * и компилятор не даст забыть HTTP-статус для нового исключения.
 */
public abstract sealed class BookingException extends RuntimeException
        permits EventNotFoundException, EventNotOnSaleException, BookingNotFoundException,
        SeatUnavailableException, InvalidSeatSelectionException, IllegalBookingTransitionException,
        IdempotencyKeyReusedException {

    protected BookingException(String message) {
        super(message);
    }
}
