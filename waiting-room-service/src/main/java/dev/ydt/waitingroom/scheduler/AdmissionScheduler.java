package dev.ydt.waitingroom.scheduler;

import dev.ydt.waitingroom.service.QueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdmissionScheduler {

    private final QueueService queueService;

    @Scheduled(fixedRateString = "${queue.admit-interval}", initialDelayString = "${queue.admit-interval}")
    public void admit() {
        try {
            queueService.admitNext();
        } catch (DataAccessException e) {
            log.warn("Could not admit next users: {}", e.getMessage());
        }
    }
}
