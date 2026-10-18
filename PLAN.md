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

## График (ускоренный, синхронизирован с Google Calendar)

Проект заканчиваем к 29.11, декабрь уходит на отклики и собеседования. Слоты: пятница 15:00–19:00 и воскресенье 14:00–18:00. Подробные задачи по блокам в [ROADMAP.md](ROADMAP.md).

| Дата | Блок | Готово, когда |
|---|---|---|
| 09.10 Пт | 1. Скелет ✅ | сервис стартует, Postgres через compose |
| 16.10 Пт | 2. Домен, sealed-статусы, стратегии цены, тесты ✅ | 61 тест зелёный |
| 18.10 Вс | 3. Гонки и блокировки | двойных продаж 0, таблица сравнения стратегий |
| 23.10 Пт | 4. API Gateway, Idempotency-Key, валидация | всё через gateway, повторный POST не создаёт бронь |
| 25.10 Вс | 5. catalog-service, индексы, N+1, keyset | каталог быстрый на 100k событий |
| 26.10 Пн | Буфер: добить 3–5 | |
| 30.10 Пт | 6. Kafka, outbox, payment-service, notification-service | бронь → оплата → уведомление, повторы безопасны |
| 01.11 Вс | 7а. Redis: холды с TTL, кэш каталога | холд истекает сам, каталог из кэша |
| 06.11 Пт | 7б. MongoDB (история), Elasticsearch (поиск) | история из Mongo, поиск с опечатками |
| 08.11 Вс | 8а. Flash sale: виртуальная очередь, Lua-hold, backpressure | 10k пользователей, 0 двойных продаж |
| 13.11 Пт | 8б. Dockerfile, compose apps, GitHub Actions | проект одной командой, CI зелёный |
| 15.11 Вс | 9. Rate limiter, circuit breaker, saga | сбои оплаты не роняют систему, лишнее → 429 |
| 20.11 Пт | ✔ Контрольная точка: функционал готов · 10. Observability + k6 baseline | RPS и p99 в Grafana |
| 22.11 Вс | 11. Нагрузка: узкое место, до/после | таблица цифр |
| 27.11 Пт | 12. kind + HPA, README со схемой и цифрами | README готов |
| 29.11 Вс | Резерв, рассказ о проекте, резюме | ✔ проект готов |
| Декабрь | Пятницы: отклики и собеседования | |

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
