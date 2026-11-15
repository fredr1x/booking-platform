package dev.ydt.booking.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Контейнеры Postgres, Kafka, Redis и один Spring-контекст на все интеграционные тесты.
 * Контейнер стартует в static-блоке и живёт до конца JVM (singleton container pattern).
 * <p>
 * Вместо catalog-service поднимается HTTP-заглушка из JDK с теми же демо-событиями, что в каталоге:
 * так CatalogClient проверяется по-настоящему, через HTTP и JSON. Так же устроена заглушка payment-service,
 * её поведение тест переключает через {@link #paymentMode}.
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

    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");

    @ServiceConnection
    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    /** Сколько HTTP-запросов дошло до заглушки каталога. */
    protected static final AtomicInteger CATALOG_REQUESTS = new AtomicInteger();

    /** Как отвечает заглушка платёжки: SLOW отвечает дольше таймаута клиента, ERROR — 500. */
    protected enum PaymentMode {
        APPROVE,
        DECLINE,
        ERROR,
        SLOW
    }

    protected static volatile PaymentMode paymentMode = PaymentMode.APPROVE;

    /** Если задан, authorize ждёт его (не дольше таймаута клиента): тест успевает что-то сделать посреди оплаты. */
    protected static volatile CountDownLatch paymentGate;

    /** Сколько HTTP-запросов дошло до заглушки платёжки (authorize и refund). */
    protected static final AtomicInteger PAYMENT_REQUESTS = new AtomicInteger();

    /** paymentId, по которым пришёл успешный refund. */
    protected static final Set<String> REFUNDED_PAYMENTS = ConcurrentHashMap.newKeySet();

    private static final HttpServer CATALOG = startStub("/api/v1/events/", AbstractIntegrationTest::catalogResponse);
    private static final HttpServer PAYMENT = startStub("/api/v1/payments", AbstractIntegrationTest::paymentResponse);

    static {
        POSTGRES.start();
        KAFKA.start();
        REDIS.start();
    }

    @Autowired
    protected MockMvc mvc;

    @DynamicPropertySource
    static void catalogUrl(DynamicPropertyRegistry registry) {
        registry.add("catalog.url", AbstractIntegrationTest::catalogUrl);
        registry.add("payment.url", () -> "http://localhost:" + PAYMENT.getAddress().getPort());
    }

    protected static String catalogUrl() {
        return "http://localhost:" + CATALOG.getAddress().getPort();
    }

    private static HttpServer startStub(String path, HttpHandler handler) {
        try {
            // тест на гонку шлёт в каталог 1000 запросов разом: нужны большая очередь соединений
            // и поток на запрос, иначе заглушка сама станет узким местом и начнёт отказывать
            var server = HttpServer.create(new InetSocketAddress(0), 2000);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext(path, handler);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void catalogResponse(HttpExchange exchange) throws IOException {
        CATALOG_REQUESTS.incrementAndGet();
        String id = exchange.getRequestURI().getPath().substring("/api/v1/events/".length());
        var now = Instant.now();
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

    // authorize: POST /api/v1/payments с Idempotency-Key, refund: POST /api/v1/payments/{id}/refund
    private static void paymentResponse(HttpExchange exchange) throws IOException {
        PAYMENT_REQUESTS.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        boolean refund = path.endsWith("/refund");
        String paymentId = refund
                ? path.substring("/api/v1/payments/".length(), path.length() - "/refund".length())
                : exchange.getRequestHeaders().getFirst("Idempotency-Key");
        CountDownLatch gate = paymentGate;
        if (gate != null && !refund) {
            try {
                gate.await(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        PaymentMode mode = paymentMode;
        if (mode == PaymentMode.SLOW) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (mode == PaymentMode.ERROR) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            return;
        }
        String status = refund ? "REFUNDED" : mode == PaymentMode.DECLINE ? "FAILED" : "SUCCEEDED";
        if (refund) {
            REFUNDED_PAYMENTS.add(paymentId);
        }
        byte[] bytes = """
                {"paymentId": "%s", "status": "%s", "failureReason": null}
                """.formatted(paymentId, status).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
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
