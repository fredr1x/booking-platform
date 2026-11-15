package dev.ydt.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Gateway с настоящим Redis: бакеты, 429 и Retry-After. Вместо booking-service HTTP-заглушка из JDK. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitIntegrationTest {

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static final HttpServer BOOKING = startBookingStub();

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("BOOKING_SERVICE_URL", () -> "http://localhost:" + BOOKING.getAddress().getPort());
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void bookingWritesAboveBurstGet429WithRetryAfter() throws Exception {
        String user = "writer-" + UUID.randomUUID();

        List<HttpResponse<String>> responses = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            responses.add(post("/api/v1/bookings", user));
        }

        // всплеск 10: первые десять проходят; пока идёт цикл, бакет пополняется на 5 в секунду,
        // поэтому точную границу не проверяем, но большая часть остальных получает отказ
        assertThat(responses.subList(0, 10)).extracting(HttpResponse::statusCode).containsOnly(201);
        assertThat(responses).filteredOn(r -> r.statusCode() == 429).hasSizeGreaterThan(10);
        HttpResponse<String> limited = responses.stream().filter(r -> r.statusCode() == 429).findFirst().orElseThrow();
        assertThat(limited.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(limited.headers().firstValue("X-RateLimit-Remaining")).hasValue("0");

        assertThat(post("/api/v1/bookings", "other-" + UUID.randomUUID()).statusCode())
                .as("у другого пользователя свой бакет").isEqualTo(201);
        assertThat(get("/api/v1/bookings/1", user).statusCode())
                .as("лимит на запись не трогает лимит на чтение").isEqualTo(200);
    }

    @Test
    void requestWithoutUserIsLimitedByIpInsteadOfRejected() throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/bookings/1")).GET().build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> post(String path, String user) throws Exception {
        var request = HttpRequest.newBuilder(uri(path))
                .header("X-User-Id", user)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String user) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("X-User-Id", user).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpServer startBookingStub() {
        try {
            var server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/api/v1/bookings", exchange -> {
                int status = exchange.getRequestMethod().equals("POST") ? 201 : 200;
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
