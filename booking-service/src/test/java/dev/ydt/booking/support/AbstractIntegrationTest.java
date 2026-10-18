package dev.ydt.booking.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Один контейнер Postgres и один Spring-контекст на все интеграционные тесты.
 * Контейнер стартует в static-блоке и живёт до конца JVM (singleton container pattern).
 */
// Тест на гонку ставит в очередь к пулу из 20 соединений тысячу потоков, 2 секунды ожидания им мало.
@SpringBootTest(properties = {
        "booking.scheduling.enabled=false",
        "spring.datasource.hikari.connection-timeout=60000"
})
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mvc;
}
