package dev.ydt.payment;

import dev.ydt.booking.contracts.web.RequestIdFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@Import(RequestIdFilter.class)
@ConfigurationPropertiesScan
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
