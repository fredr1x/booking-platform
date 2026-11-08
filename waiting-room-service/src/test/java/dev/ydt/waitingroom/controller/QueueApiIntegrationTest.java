package dev.ydt.waitingroom.controller;

import dev.ydt.booking.contracts.AdmissionToken;
import dev.ydt.waitingroom.dto.queue.QueueStatusResponse;
import dev.ydt.waitingroom.entity.enums.QueueState;
import dev.ydt.waitingroom.service.QueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class QueueApiIntegrationTest {

    private static final long EVENT = 2;
    private static final String SECRET = "test-admission-token-secret-0000001";

    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    QueueService queueService;
    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void emptyRedis() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    @Test
    void firstInFirstAdmittedWithWorkingToken() throws Exception {
        for (String user : new String[]{"alice", "bob", "carol"}) {
            mvc.perform(post("/api/v1/queue/{e}/join", EVENT).header("X-User-Id", user)).andExpect(status().isOk());
            Thread.sleep(2);
        }
        mvc.perform(get("/api/v1/queue/{e}/status", EVENT).header("X-User-Id", "carol"))
                .andExpect(jsonPath("$.state").value("WAITING"))
                .andExpect(jsonPath("$.position").value(3))
                .andExpect(jsonPath("$.estimatedWaitSeconds").value(5400));

        assertThat(queueService.admitNext()).isEqualTo(2);

        QueueStatusResponse alice = queueService.status(EVENT, "alice");
        assertThat(alice.state()).isEqualTo(QueueState.ADMITTED);
        assertThat(AdmissionToken.isValid(SECRET, alice.admissionToken(), EVENT, "alice", Instant.now())).isTrue();
        assertThat(alice.tokenExpiresAt()).isBefore(Instant.now().plusSeconds(601));
        assertThat(queueService.status(EVENT, "bob").state()).isEqualTo(QueueState.ADMITTED);
        assertThat(queueService.status(EVENT, "carol").position()).as("очередь сдвинулась").isEqualTo(1);
    }

    @Test
    void rejoinKeepsPlaceAndAdmittedStaysAdmitted() throws Exception {
        queueService.join(EVENT, "alice");
        Thread.sleep(2);
        queueService.join(EVENT, "bob");
        Thread.sleep(2);

        assertThat(queueService.join(EVENT, "alice").position()).as("повторный вход не в конец").isEqualTo(1);

        queueService.admitNext();
        assertThat(queueService.join(EVENT, "alice").state()).isEqualTo(QueueState.ADMITTED);
        assertThat(redis.opsForZSet().size("queue:{2}")).as("пропущенный не встал в очередь снова").isZero();
    }

    @Test
    void oneTickPerIntervalEvenWithSeveralInstances() {
        IntStream.range(0, 10).forEach(i -> queueService.join(EVENT, "user-" + i));

        assertThat(queueService.admitNext()).isEqualTo(2);
        assertThat(queueService.admitNext()).as("второй инстанс в том же интервале").isZero();
        assertThat(redis.opsForZSet().size("queue:{2}")).isEqualTo(8);
    }

    @Test
    void concurrentJoinsAreAllCountedOnce() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, 1000).forEach(i -> {
                executor.submit(() -> queueService.join(EVENT, "user-" + i));
                executor.submit(() -> queueService.join(EVENT, "user-" + i));
            });
        }

        assertThat(redis.opsForZSet().size("queue:{2}")).isEqualTo(1000);
    }

    @Test
    void unknownEventAndUserAreRejected() throws Exception {
        mvc.perform(post("/api/v1/queue/{e}/join", 3).header("X-User-Id", "alice")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/queue/{e}/status", EVENT).header("X-User-Id", "nobody")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/queue/{e}/join", EVENT)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/queue/{e}/join", EVENT).header("X-User-Id", "x".repeat(65)))
                .andExpect(status().isBadRequest());
    }
}
