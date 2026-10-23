package dev.ydt.booking.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Id пользователя из заголовка X-User-Id. Аутентификации нет по замыслу проекта.
 * Разбирает {@link CurrentUserArgumentResolver}, без заголовка ответ 400.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Parameter(in = ParameterIn.HEADER, name = CurrentUserArgumentResolver.HEADER, required = true)
public @interface CurrentUser {
}
