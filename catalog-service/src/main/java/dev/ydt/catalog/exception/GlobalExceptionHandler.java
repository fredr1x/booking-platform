package dev.ydt.catalog.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// Ошибки Spring MVC (валидация параметров, не тот тип) переводит в ProblemDetail базовый класс.
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(EventNotFoundException.class)
    public ProblemDetail handleNotFound(EventNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "EventNotFound", ex);
    }

    @ExceptionHandler(InvalidCursorException.class)
    public ProblemDetail handleInvalidCursor(InvalidCursorException ex) {
        return problem(HttpStatus.BAD_REQUEST, "InvalidCursor", ex);
    }

    private static ProblemDetail problem(HttpStatus status, String title, RuntimeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle(title);
        return problem;
    }
}
