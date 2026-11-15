package dev.ydt.payment.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Имитация сбоя банка: платёж не создан, повтор с тем же id безопасен. */
@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class PaymentProviderException extends RuntimeException {

    public PaymentProviderException() {
        super("Payment provider failed, retry the request");
    }
}
