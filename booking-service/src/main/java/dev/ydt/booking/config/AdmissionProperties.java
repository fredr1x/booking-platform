package dev.ydt.booking.config;

import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Set;

/**
 * @param flashSaleEvents события, которые бронируются только с пропуском из очереди
 * @param tokenSecret     общий секрет с waiting-room-service, короткий секрет не даст стартовать
 */
// shortcut: список событий в настройках; появится API каталога — флаг flash sale у самого события
@Validated
@ConfigurationProperties("booking.admission")
public record AdmissionProperties(
        Set<Long> flashSaleEvents,
        @Size(min = 32) String tokenSecret
) {
}
