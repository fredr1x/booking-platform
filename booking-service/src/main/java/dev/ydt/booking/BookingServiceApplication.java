package dev.ydt.booking;

import dev.ydt.booking.contracts.web.RequestIdFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@Import(RequestIdFilter.class)
@ConfigurationPropertiesScan
public class BookingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }
}
