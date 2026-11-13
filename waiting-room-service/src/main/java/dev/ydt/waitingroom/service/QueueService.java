package dev.ydt.waitingroom.service;

import dev.ydt.booking.contracts.AdmissionToken;
import dev.ydt.waitingroom.config.QueueProperties;
import dev.ydt.waitingroom.dto.queue.QueueStatusResponse;
import dev.ydt.waitingroom.entity.enums.QueueState;
import dev.ydt.waitingroom.repository.QueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class QueueService {

    private static final long MAX_POLL_SECONDS = 10;

    private final QueueRepository queueRepository;
    private final QueueProperties properties;

    public QueueStatusResponse join(long eventId, String userId) {
        requireQueuedEvent(eventId);
        if (queueRepository.admissionTtlMillis(eventId, userId) <= 0) {
            queueRepository.join(eventId, userId, Instant.now());
        }
        return status(eventId, userId);
    }

    public QueueStatusResponse status(long eventId, String userId) {
        requireQueuedEvent(eventId);
        long admissionTtl = queueRepository.admissionTtlMillis(eventId, userId);
        if (admissionTtl > 0) {
            Instant expiresAt = Instant.now().plusMillis(admissionTtl);
            return new QueueStatusResponse(QueueState.ADMITTED, null, null, null,
                    AdmissionToken.issue(properties.tokenSecret(), eventId, userId, expiresAt), expiresAt);
        }
        Long rank = queueRepository.rank(eventId, userId);
        if (rank == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not in the queue");
        }
        long position = rank + 1;
        long waitSeconds = (long) Math.ceil(position * secondsPerUser());
        // дальние опрашивают реже: 10 000 ждущих не должны создавать 10 000 запросов в секунду
        long retryAfter = Math.clamp(waitSeconds / 2, 1, MAX_POLL_SECONDS);
        return new QueueStatusResponse(QueueState.WAITING, position, waitSeconds, retryAfter, null, null);
    }

    public long admitNext() {
        // отметка прохода живёт чуть меньше интервала, иначе следующий проход наткнулся бы на предыдущую
        Duration tickTtl = properties.admitInterval().multipliedBy(9).dividedBy(10);
        return properties.eventIds().stream()
                .mapToLong(eventId -> queueRepository.admit(eventId, properties.admitPerTick(),
                        properties.admissionTtl(), tickTtl))
                .sum();
    }

    private double secondsPerUser() {
        return properties.admitInterval().toMillis() / 1000.0 / properties.admitPerTick();
    }

    private void requireQueuedEvent(long eventId) {
        if (!properties.eventIds().contains(eventId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event " + eventId + " has no queue");
        }
    }
}
