package dev.ydt.booking.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * Все ошибки в формате RFC 7807 (ProblemDetail). Ошибки Spring MVC (нет заголовка, кривой JSON,
 * не тот тип параметра) уже переведены в ProblemDetail базовым классом, здесь только доменные
 * и подробности валидации.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Одна ошибка валидации: какое поле и что с ним не так. */
    public record FieldViolation(String field, String message) {
    }

    // @Valid на теле запроса
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return handleExceptionInternal(ex, withErrors(ex.getBody(), errors), headers, status, request);
    }

    // Ограничения прямо на параметрах (заголовки, query). Если они есть, Spring валидирует весь метод разом,
    // и ошибки @Valid-тела приходят сюда же как ParameterErrors, а не в handleMethodArgumentNotValid.
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldViolation> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result instanceof ParameterErrors bodyErrors
                        ? bodyErrors.getFieldErrors().stream()
                                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                        : result.getResolvableErrors().stream()
                                .map(error -> new FieldViolation(result.getMethodParameter().getParameterName(),
                                        error.getDefaultMessage())))
                .toList();
        return handleExceptionInternal(ex, withErrors(ex.getBody(), errors), headers, status, request);
    }

    private static ProblemDetail withErrors(ProblemDetail problem, List<FieldViolation> errors) {
        problem.setTitle("ValidationFailed");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(BookingException.class)
    public ProblemDetail handleBooking(BookingException ex) {
        HttpStatus status = switch (ex) {
            case EventNotFoundException e -> HttpStatus.NOT_FOUND;
            case BookingNotFoundException e -> HttpStatus.NOT_FOUND;
            case SeatUnavailableException e -> HttpStatus.CONFLICT;
            case IllegalBookingTransitionException e -> HttpStatus.CONFLICT;
            case EventNotOnSaleException e -> HttpStatus.UNPROCESSABLE_CONTENT;
            case InvalidSeatSelectionException e -> HttpStatus.BAD_REQUEST;
            case IdempotencyKeyReusedException e -> HttpStatus.UNPROCESSABLE_CONTENT;
            case CatalogUnavailableException e -> HttpStatus.SERVICE_UNAVAILABLE;
            case AdmissionRequiredException e -> HttpStatus.FORBIDDEN;
        };
        var problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle(ex.getClass().getSimpleName().replace("Exception", ""));
        if (ex instanceof SeatUnavailableException e) {
            problem.setProperty("seatIds", e.getSeatIds());
        }
        return problem;
    }

    // Быстрый отказ при перегрузе: Retry-After говорит клиенту, когда повторить, вместо ожидания таймаута.
    @ExceptionHandler(BookingOverloadedException.class)
    public ResponseEntity<ProblemDetail> handleOverloaded(BookingOverloadedException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Overloaded");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(BookingOverloadedException.RETRY_AFTER_SECONDS))
                .body(problem);
    }

    // Две транзакции одновременно поменяли одну бронь или место (@Version).
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Resource was modified concurrently, retry the request");
        problem.setTitle("ConcurrentModification");
        return problem;
    }

    // Последний рубеж: блокировки в коде пропустили гонку, но её поймало ограничение в БД
    // (например, уникальный индекс uq_booking_seats_active_seat).
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleIntegrityViolation(DataIntegrityViolationException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Request conflicts with the current state, retry the request");
        problem.setTitle("Conflict");
        return problem;
    }
}
