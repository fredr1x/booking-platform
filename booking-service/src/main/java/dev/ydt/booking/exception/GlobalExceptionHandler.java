package dev.ydt.booking.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Минимальная версия: доменные ошибки → RFC 7807. Валидацию и остальное расширяем в блоке 4.
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BookingException.class)
    public ProblemDetail handleBooking(BookingException ex) {
        HttpStatus status = switch (ex) {
            case EventNotFoundException e -> HttpStatus.NOT_FOUND;
            case BookingNotFoundException e -> HttpStatus.NOT_FOUND;
            case SeatUnavailableException e -> HttpStatus.CONFLICT;
            case IllegalBookingTransitionException e -> HttpStatus.CONFLICT;
            case EventNotOnSaleException e -> HttpStatus.UNPROCESSABLE_CONTENT;
            case InvalidSeatSelectionException e -> HttpStatus.BAD_REQUEST;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle(ex.getClass().getSimpleName().replace("Exception", ""));
        if (ex instanceof SeatUnavailableException e) {
            problem.setProperty("seatIds", e.getSeatIds());
        }
        return problem;
    }

    // Две транзакции одновременно поменяли одну бронь или место (@Version).
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Resource was modified concurrently, retry the request");
        problem.setTitle("ConcurrentModification");
        return problem;
    }

    // Последний рубеж: блокировки в коде пропустили гонку, но её поймало ограничение в БД
    // (например, уникальный индекс uq_booking_seats_active_seat).
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleIntegrityViolation(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Request conflicts with the current state, retry the request");
        problem.setTitle("Conflict");
        return problem;
    }
}
