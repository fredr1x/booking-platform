# Booking Platform: high-load бронирование билетов

Цель: показать, что система **не продаёт одно место дважды** и **не падает под пиком** (старт продаж, 10k+ одновременных пользователей). Аутентификации нет сознательно: пользователь передаётся заголовком `X-User-Id`, весь фокус на конкурентности, блокировках и устойчивости.

## Стек

| Область | Технология | Зачем в проекте |
|---|---|---|
| Язык, фреймворк | Java 21 (virtual threads), Spring Boot 4, Maven multi-module | Основа сервисов |
| Бойлерплейт | Lombok, MapStruct | Геттеры и конструкторы, маппинг сущность → DTO |
| Основная БД | PostgreSQL 16 + Flyway | Места, брони, outbox. Блокировки: optimistic, `FOR UPDATE SKIP LOCKED` |
| Кэш и координация | Redis 7 | Hold мест с TTL, Lua-скрипты, rate limiter, виртуальная очередь, idempotency, кэш каталога |
| Брокер | Kafka (KRaft, без ZooKeeper) | События брони и оплаты, outbox, saga |
| NoSQL | MongoDB | История броней и журнал уведомлений (read-модель) |
| Поиск | Elasticsearch (single-node) | Поиск событий |
| Устойчивость | Resilience4j | Circuit breaker, retry, timeout, bulkhead к payment |
| Gateway | Spring Cloud Gateway | Маршрутизация, rate limit, request id |
| Observability | Micrometer, Prometheus, Grafana, Loki, (Tempo по желанию) | RPS, p99, логи по correlation id |
| Нагрузка | k6 | Цифры до и после для README |
| Инфра | Docker Compose (профили), kind + HPA в конце | Локальный запуск на 16 ГБ |

## Микросервисы

```
client / k6
    │
api-gateway ── rate limiter (Redis token bucket), X-Request-Id
    │
    ├── waiting-room-service  виртуальная очередь на flash sale (Redis ZSET, admission-токены)
    ├── catalog-service       события, площадки, схема мест; Postgres + Redis cache + Elasticsearch
    ├── booking-service       hold → reserve → confirm; Postgres + Redis + outbox → Kafka
    ├── payment-service       stub с настраиваемыми задержками и ошибками (цель для circuit breaker)
    └── notification-service  Kafka consumer, идемпотентность, история в MongoDB
```

Поток брони (saga, хореография через Kafka):
1. `POST /bookings` с `Idempotency-Key` → атомарный hold места в Redis (Lua, TTL 10 мин) → запись `PENDING` в Postgres + outbox в одной транзакции.
2. Outbox-релей публикует `BookingCreated` → payment-service.
3. `PaymentSucceeded` → `CONFIRMED`; `PaymentFailed` / таймаут → компенсация: место освобождается.
4. notification-service пишет историю в MongoDB и «отправляет» уведомление (лог).

## Бюджет памяти (16 ГБ ноутбук)

ОС + IDE + браузер ≈ 7–8 ГБ, на Docker остаётся ≈ 6–7 ГБ. У каждого контейнера жёсткий `mem_limit`.

| Контейнер | Лимит | Настройка |
|---|---|---|
| 5–6 Spring-сервисов | по 384 МБ | `-XX:MaxRAMPercentage=70`, virtual threads |
| PostgreSQL | 512 МБ | `shared_buffers=128MB` |
| Redis | 256 МБ | `maxmemory 192mb` |
| Kafka (KRaft, 1 брокер) | 768 МБ | `KAFKA_HEAP_OPTS=-Xmx512m` |
| MongoDB | 512 МБ | `--wiredTigerCacheSizeGB 0.25` |
| Elasticsearch | 1 ГБ | `-Xms512m -Xmx512m`, профиль `search` |
| Prometheus + Grafana + Loki | ≈ 700 МБ | профиль `obs` |
| **Итого всё сразу** | **≈ 6 ГБ** | |

Compose-профили, чтобы не держать всё одновременно: `core` (Postgres, Redis, Kafka, сервисы), `search`, `obs`, `nosql`. k6 запускается с хоста. В kind разворачиваются только сервисы, инфраструктура остаётся в compose.

Отличия от первоначального плана ради памяти:
- **RabbitMQ убран**: Kafka уже есть, второй брокер ≈ 300–500 МБ без новой идеи для резюме.
- **ELK заменён на Loki**: Elasticsearch с Logstash и Kibana занимает 2–3 ГБ, Loki ≈ 150 МБ. Elasticsearch остаётся только для поиска событий.

## План по пятницам (синхронизирован с Google Calendar)

Подробные задачи по каждой неделе в [ROADMAP.md](ROADMAP.md).

| Дата | Блок | Готово, когда |
|---|---|---|
| 09.10 | 1. Скелет: Maven multi-module, compose с Postgres, booking-service, модель события и мест, первая миграция | сервис стартует, Postgres поднят через compose |
| 16.10 | 2. Домен: статусы брони на sealed-типах и records, State-машина, JUnit 5 + Mockito | переходы статусов покрыты тестами |
| 23.10 | 3. Гонки и блокировки: тест на тысячи потоков с двойной продажей, фикс в Java, optimistic/pessimistic, `SKIP LOCKED` | двойных продаж ноль, есть сравнение подходов по скорости |
| 30.10 | 4. **Слои, валидация, API Gateway, идемпотентность** *(было: Spring Security)* | понятные ошибки, запросы через gateway, повторный POST не создаёт вторую бронь |
| 06.11 | 5. Каталог: catalog-service, индексы и EXPLAIN, N+1, JdbcTemplate-отчёт, keyset-пагинация | запросы каталога быстрые, план разобран |
| 13.11 | 6. Kafka: outbox, идемпотентный consumer, DLQ, notification-service | повторная доставка ничего не ломает |
| 20.11 | ✔ Контрольная точка: блоки 1–6 | |
| 20.11 | 7. Redis, MongoDB, Elasticsearch: hold с TTL, cache-aside, история в Mongo, поиск | бронь истекает сама, каталог из кэша, поиск работает |
| 27.11 | 8. **Flash sale: виртуальная очередь, Lua-hold, backpressure, Testcontainers, CI** *(было: Keycloak)* | 10k пользователей на одно событие, двойных продаж ноль, CI зелёный |
| 04.12 | 9. Rate limiter (token bucket в Redis), Resilience4j к payment, saga с компенсацией | при сбоях оплаты место возвращается, лишнее отсекается 429 |
| 04.12 | ✔ Контрольная точка: блоки 7–9 | |
| 11.12 | 10. Observability: Prometheus, Grafana, Loki с correlation id, k6 baseline | в Grafana видны RPS и p99 |
| 18.12 | 11–12. kind + HPA, нагрузка, поиск узкого места, README с цифрами и схемой | цифры до/после в README |
| 18.12 | ✔ Контрольная точка: блоки 10–12 | |
| 25.12 | Резерв: добить хвосты, рассказ для собеса | |

## Почему вместо Keycloak — flash sale

«Настроил realm и роли в Keycloak» есть почти в каждом резюме и про high-load ничего не говорит. Виртуальная очередь — это то, как работают Ticketmaster и Queue-it на старте продаж:
- пользователь получает позицию в Redis Sorted Set, клиент опрашивает её;
- сервис пропускает N человек в секунду и выдаёт admission-токен с TTL, без токена booking отвечает 403;
- hold места делается одним Lua-скриптом (проверка и резерв атомарно), Postgres видит только тех, кто прошёл;
- при перегрузе система отвечает `429/503 + Retry-After`, а не падает.

Строка в резюме: *«Спроектировал виртуальную очередь и атомарный резерв мест на Redis Lua: 10k одновременных пользователей на одно событие, p99 < X мс, 0 двойных продаж»* (X берём из k6).

## Метрики для README (снимаем k6 в блоках 3, 8, 10, 11)

- Двойные продажи при N потоках: до фикса / после каждого способа блокировки.
- RPS и p99 на `POST /bookings`: Postgres-only против Redis hold + очередь.
- Поведение при отказе payment (50% ошибок): без circuit breaker / с ним.
- Доля 429 при превышении лимита, стабильность latency остальных запросов.
