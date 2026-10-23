package dev.ydt.booking.controller;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER = "X-User-Id";

    // bookings.user_id VARCHAR(64): длинный id упал бы в БД с 500, отвечаем 400 сразу
    private static final int MAX_LENGTH = 64;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class) && parameter.getParameterType() == String.class;
    }

    @Override
    public String resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory)
            throws MissingRequestHeaderException {
        String userId = webRequest.getHeader(HEADER);
        if (userId == null || userId.isBlank()) {
            throw new MissingRequestHeaderException(HEADER, parameter);
        }
        if (userId.length() > MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    HEADER + " must be at most " + MAX_LENGTH + " characters");
        }
        return userId;
    }
}
