package dev.ydt.booking.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Один контейнер Postgres и один Spring-контекст на все интеграционные тесты.
 * Контейнер стартует в static-блоке и живёт до конца JVM (singleton container pattern).
 * <p>
 * Вместо catalog-service поднимается HTTP-заглушка из JDK с теми же демо-событиями, что в каталоге:
 * так CatalogClient проверяется по-настоящему, через HTTP и JSON.
 */
// Настройки тестов в application-test.yml: демо-данные, без планировщика, длинное ожидание пула.
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    /** Событие, на котором заглушка каталога отвечает 500. */
    protected static final long BROKEN_CATALOG_EVENT_ID = 500;

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    /** Сколько HTTP-запросов дошло до заглушки каталога. */
    protected static final AtomicInteger CATALOG_REQUESTS = new AtomicInteger();

    private static final HttpServer CATALOG = startCatalogStub();

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mvc;

    @DynamicPropertySource
    static void catalogUrl(DynamicPropertyRegistry registry) {
        registry.add("catalog.url", AbstractIntegrationTest::catalogUrl);
    }

    protected static String catalogUrl() {
        return "http://localhost:" + CATALOG.getAddress().getPort();
    }

    private static HttpServer startCatalogStub() {
        try {
            // тест на гонку шлёт в каталог 1000 запросов разом: нужны большая очередь соединений
            // и поток на запрос, иначе заглушка сама станет узким местом и начнёт отказывать
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 2000);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/api/v1/events/", AbstractIntegrationTest::catalogResponse);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void catalogResponse(HttpExchange exchange) throws IOException {
        CATALOG_REQUESTS.incrementAndGet();
        String id = exchange.getRequestURI().getPath().substring("/api/v1/events/".length());
        Instant now = Instant.now();
        String body = switch (id) {
            // Jazz Night: продажи открылись сутки назад, поэтому действует early bird
            case "1" -> event(1, "ON_SALE", now.minus(Duration.ofDays(1)));
            case "2" -> event(2, "ON_SALE", now.minus(Duration.ofHours(1)));
            case "3" -> event(3, "DRAFT", null);
            default -> null;
        };
        int status = body != null ? 200 : id.equals(String.valueOf(BROKEN_CATALOG_EVENT_ID)) ? 500 : 404;
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    // лишние поля (title, venue) как в настоящем ответе каталога: клиент должен их пропускать
    private static String event(long id, String status, Instant salesOpenAt) {
        String opens = salesOpenAt == null ? "null" : "\"" + salesOpenAt + "\"";
        return """
                {"id": %d, "title": "Event %d", "venue": "Arena", "status": "%s", "salesOpenAt": %s}
                """.formatted(id, id, status, opens);
    }
}
