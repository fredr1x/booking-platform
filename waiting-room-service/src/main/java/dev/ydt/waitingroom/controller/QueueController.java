package dev.ydt.waitingroom.controller;

import dev.ydt.waitingroom.dto.queue.QueueStatusResponse;
import dev.ydt.waitingroom.service.QueueService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/queue/{eventId}")
@RequiredArgsConstructor
public class QueueController {

    private static final String USER_HEADER = "X-User-Id";

    private final QueueService queueService;

    @PostMapping("/join")
    public QueueStatusResponse join(@PathVariable long eventId,
                                    @RequestHeader(USER_HEADER) @NotBlank @Size(max = 64) String userId) {
        return queueService.join(eventId, userId);
    }

    @GetMapping("/status")
    public QueueStatusResponse status(@PathVariable long eventId,
                                      @RequestHeader(USER_HEADER) @NotBlank @Size(max = 64) String userId) {
        return queueService.status(eventId, userId);
    }
}
