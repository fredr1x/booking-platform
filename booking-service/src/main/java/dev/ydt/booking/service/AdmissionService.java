package dev.ydt.booking.service;

import dev.ydt.booking.config.AdmissionProperties;
import dev.ydt.booking.contracts.AdmissionToken;
import dev.ydt.booking.exception.AdmissionRequiredException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** На событие flash sale бронируют только те, кого пропустила виртуальная очередь. */
@Service
@RequiredArgsConstructor
public class AdmissionService {

    private final AdmissionProperties properties;
    private final Clock clock;

    public void check(Long eventId, String userId, String token) {
        if (properties.flashSaleEvents().contains(eventId)
                && !AdmissionToken.isValid(properties.tokenSecret(), token, eventId, userId, clock.instant())) {
            throw new AdmissionRequiredException(eventId);
        }
    }
}
