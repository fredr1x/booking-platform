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

/** Gateway проксирует на две заглушки из JDK (booking и catalog), они запоминают, что до них дошло. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestIdFilterTest {

    private static final AtomicReference<String> RECEIVED_ID = new AtomicReference<>();
    private static final AtomicReference<String> RECEIVED_HOST = new AtomicReference<>();
    private static final AtomicReference<String> BOOKING_PATH = new AtomicReference<>();
    private static final AtomicReference<String> CATALOG_PATH = new AtomicReference<>();
    private static final HttpServer BOOKING = startDownstream(BOOKING_PATH);
    private static final HttpServer CATALOG = startDownstream(CATALOG_PATH);

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void routeToDownstream(DynamicPropertyRegistry registry) {
        registry.add("BOOKING_SERVICE_URL", () -> "http://localhost:" + BOOKING.getAddress().getPort());
        registry.add("CATALOG_SERVICE_URL", () -> "http://localhost:" + CATALOG.getAddress().getPort());
    }

    @AfterAll
    static void stopDownstream() {
        BOOKING.stop(0);
        CATALOG.stop(0);
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
    void eventsGoToCatalogAndInventoryToBooking() throws Exception {
        assertThat(routedTo("/api/v1/events?city=Almaty")).isEqualTo(CATALOG_PATH);
        assertThat(routedTo("/api/v1/events/7")).isEqualTo(CATALOG_PATH);
        assertThat(routedTo("/api/v1/events/7/seats")).isEqualTo(BOOKING_PATH);
        assertThat(routedTo("/api/v1/events/7/availability")).isEqualTo(BOOKING_PATH);
        assertThat(routedTo("/api/v1/bookings/5")).isEqualTo(BOOKING_PATH);
    }

    // страница симулятора лежит в static/ самого gateway, не проксируется
    @Test
    void simulatorPageIsServed() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("<title>Booking Simulator</title>");
    }

    @Test
    void unknownPathIsNotRouted() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/admin")).build();

        assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
    }

    private HttpResponse<Void> send(String requestId) throws Exception {
        RECEIVED_ID.set(null);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/bookings"));
        if (requestId != null) {
            request.header(RequestIdFilter.HEADER, requestId);
        }
        HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
        return response;
    }

    private AtomicReference<String> routedTo(String path) throws Exception {
        BOOKING_PATH.set(null);
        CATALOG_PATH.set(null);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        return BOOKING_PATH.get() != null ? BOOKING_PATH : CATALOG_PATH;
    }

    private static HttpServer startDownstream(AtomicReference<String> receivedPath) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/", exchange -> {
                receivedPath.set(exchange.getRequestURI().getPath());
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
