package dev.ydt.catalog.exception;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    // Elasticsearch или база недоступны: это сбой зависимости, клиенту 503 и когда повторить, а не 500
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ProblemDetail> handleUnavailable(DataAccessResourceFailureException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Search or storage is temporarily unavailable, retry later");
        problem.setTitle("ServiceUnavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5").body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String title, RuntimeException ex) {
        var problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle(title);
        return problem;
    }
}
