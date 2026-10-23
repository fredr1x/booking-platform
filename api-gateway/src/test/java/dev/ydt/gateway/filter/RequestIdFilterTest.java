package dev.ydt.gateway.filter;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Gateway проксирует на заглушку из JDK, которая запоминает, какой X-Request-Id до неё дошёл. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestIdFilterTest {

    private static final AtomicReference<String> RECEIVED_ID = new AtomicReference<>();
    private static final AtomicReference<String> RECEIVED_HOST = new AtomicReference<>();
    private static final HttpServer DOWNSTREAM = startDownstream();

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void routeToDownstream(DynamicPropertyRegistry registry) {
        registry.add("BOOKING_SERVICE_URL", () -> "http://localhost:" + DOWNSTREAM.getAddress().getPort());
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.stop(0);
    }

    @Test
    void generatesIdWhenMissing() throws Exception {
        HttpResponse<Void> response = send(null);

        String id = response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(RECEIVED_ID.get()).isEqualTo(id);
    }

    @Test
    void keepsValidClientId() throws Exception {
        HttpResponse<Void> response = send("client-req-42");

        assertThat(response.headers().firstValue(RequestIdFilter.HEADER)).hasValue("client-req-42");
        assertThat(RECEIVED_ID.get()).isEqualTo("client-req-42");
    }

    @Test
    void replacesMalformedClientId() throws Exception {
        HttpResponse<Void> response = send("<script>alert(1)</script>");

        String id = response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(RECEIVED_ID.get()).isEqualTo(id);
    }

    // по нему booking-service строит Location на адрес gateway, а не на свой внутренний
    @Test
    void forwardsOriginalHost() throws Exception {
        send(null);

        assertThat(RECEIVED_HOST.get()).isEqualTo("localhost:" + port);
    }

    @Test
    void unknownPathIsNotRouted() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/admin")).build();

        assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
    }

    private HttpResponse<Void> send(String requestId) throws Exception {
        RECEIVED_ID.set(null);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events"));
        if (requestId != null) {
            request.header(RequestIdFilter.HEADER, requestId);
        }
        HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
        return response;
    }

    private static HttpServer startDownstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", exchange -> {
                RECEIVED_ID.set(exchange.getRequestHeaders().getFirst(RequestIdFilter.HEADER));
                RECEIVED_HOST.set(exchange.getRequestHeaders().getFirst("X-Forwarded-Host"));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
