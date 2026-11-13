package dev.ydt.waitingroom.exception;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// Ошибки Spring MVC (валидация, нет заголовка, ResponseStatusException) переводит в ProblemDetail базовый класс.
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // Вся очередь живёт в Redis: без него входить некуда, клиент повторит позже.
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> handleRedisDown(DataAccessException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Queue is temporarily unavailable");
        problem.setTitle("QueueUnavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }
}
