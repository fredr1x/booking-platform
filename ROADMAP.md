# Дорожная карта: по неделям

Детальный план реализации. Общая идея, стек и бюджет памяти в [PLAN.md](PLAN.md). Даты и слоты в таблице «График» в [PLAN.md](PLAN.md), синхронизировано с Google Calendar. Проект заканчиваем к 29.11.

## Итоговая архитектура

**6 микросервисов + 1 общий модуль контрактов.** Аутентификации нет, пользователь приходит заголовком `X-User-Id`.

| # | Сервис | Порт | Появляется | Хранилища | Ответственность |
|---|---|---|---|---|---|
| 1 | `booking-service` | 8081 | неделя 1 | Postgres `booking`, Redis | Инвентарь мест, hold, брони, saga-оркестратор, outbox |
| 2 | `api-gateway` | 8080 | неделя 4 | Redis | Единая точка входа, маршрутизация, rate limit, `X-Request-Id` |
| 3 | `catalog-service` | 8082 | неделя 5 | Postgres `catalog`, Redis, Elasticsearch | События, площадки, цены, поиск, кэш |
| 4 | `payment-service` | 8083 | неделя 6 | Postgres `payment` | Заглушка платёжки: задержки и ошибки по настройке, результат через Kafka |
| 5 | `notification-service` | 8084 | неделя 6 | MongoDB | Уведомления (лог), история броней пользователя (read-модель) |
| 6 | `waiting-room-service` | 8085 | неделя 8 | Redis | Виртуальная очередь на старт продаж, admission-токены |
| — | `booking-contracts` | — | неделя 6 | — | Общие записи событий Kafka и имена топиков |

Один инстанс Postgres с тремя отдельными базами (database per service без лишних контейнеров).

```
                      ┌──────────────────────┐
  k6 / client ──────► │     api-gateway      │ rate limit (Redis), X-Request-Id
                      └──┬──────┬──────┬─────┘
             ┌───────────┘      │      └──────────────┐
             ▼                  ▼                     ▼
   waiting-room-service   catalog-service ◄──REST── booking-service ──REST(CB)──► payment-service
     Redis ZSET            Postgres/Redis/ES          Postgres + Redis              Postgres
                                 ▲                    │ outbox                         │
                                 │                    ▼                                ▼
                                 └──────────────── Kafka ◄─────────────────────────────┘
                                                      │
                                                      ▼
                                              notification-service ── MongoDB
```

### Финальная структура репозитория

```
booking-project/
├── pom.xml                      родитель, версии
├── booking-contracts/           события Kafka (records), топики
├── booking-service/
├── catalog-service/
├── payment-service/
├── notification-service/
├── waiting-room-service/
├── api-gateway/
├── deploy/
│   ├── compose/                 конфиги Prometheus, Grafana, Loki
│   └── k8s/                     манифесты для kind
├── load-tests/                  сценарии k6
├── docs/adr/                    решения: блокировки, saga, очередь
├── docker-compose.yml           профили: core, search, nosql, obs, apps
├── PLAN.md, ROADMAP.md, README.md
└── .github/workflows/ci.yml
```

### Как растёт проект

| После недели | Сервисов | Контейнеров инфраструктуры | Память Docker (примерно) |
|---|---|---|---|
| 1–4 | 1 → 2 | Postgres | 0.5 ГБ |
| 5 | 3 | Postgres | 0.5 ГБ |
| 6 | 5 | + Kafka | 1.3 ГБ |
| 7 | 5 | + Redis, MongoDB, Elasticsearch | 3 ГБ |
| 8 | 6 | все сервисы в контейнерах | 5 ГБ |
| 10 | 6 | + Prometheus, Grafana, Loki | 6 ГБ |
| 11 | 6 | сервисы в kind, инфраструктура в compose | 6.5 ГБ |

Пока сервисов мало, они запускаются из IDE. С недели 8 у каждого есть Dockerfile и профиль `apps` в compose.

---

## Блок 1 · 09.10 · Скелет ✅

**Сделано:** Maven multi-module, Spring Boot 4.0.8 и Java 21 (virtual threads), compose с Postgres 17 (порт 5433, 512 МБ), `booking-service` с моделью `Event` / `Seat`, Flyway, демо-данные (событие на 20 000 мест), интеграционный тест на Testcontainers.

Репозиторий: [fredr1x/booking-platform](https://github.com/fredr1x/booking-platform).

---

## Блок 2 · 16.10 · Домен и unit-тесты ✅

**Сделано:** `V2__bookings.sql`, sealed `BookingState` (State), стратегии цены `Demand > EarlyBird > Standard` (Strategy), sealed-иерархия исключений с ProblemDetail, API create / get / cancel, уборщик просроченных холдов. 61 тест: матрица переходов 5×4, агрегат, цены, сервис на Mockito, API на Testcontainers. Демо-данные переведены в repeatable-миграцию `R__demo_events.sql`.

**Задачи**
1. Миграция `V2`: таблицы `bookings` (id, event_id, user_id, status, total_price, expires_at, created_at, version) и `booking_seats` (booking_id, seat_id, price).
2. Статусы брони как `sealed interface BookingState` с records:
   `Pending(expiresAt)`, `AwaitingPayment(paymentId)`, `Confirmed(confirmedAt)`, `Cancelled(reason)`, `Expired`.
   Переходы через `switch` с pattern matching, компилятор проверяет, что все случаи разобраны.
3. Агрегат `Booking` с методами `pay()`, `confirm()`, `cancel()`, `expire()`. Недопустимый переход бросает `IllegalBookingTransitionException`.
4. Иерархия исключений: `BookingException` → `SeatUnavailableException`, `BookingNotFoundException`, `IllegalBookingTransitionException`.
5. Паттерн Strategy для цены: `PricingStrategy` с реализациями `StandardPricing`, `EarlyBirdPricing`, `DemandPricing` (цена растёт, когда продано больше 80%).
6. Эндпоинты (наивная версия, пока без защиты от гонок): `POST /api/v1/bookings`, `GET /api/v1/bookings/{id}`, `POST /api/v1/bookings/{id}/cancel`.
7. Тесты: JUnit 5, Mockito и AssertJ. `@ParameterizedTest` на всю матрицу переходов статусов, тесты стратегий цены.

**Готово, когда:** матрица переходов покрыта тестами, бронь создаётся через API.

**В резюме:** доменная модель на sealed-типах и records (Java 21).

---

## Блок 3 · 18.10 · Гонки и блокировки (ядро проекта) ✅

**Сделано:** 6 стратегий холда в `service/locking` (по умолчанию `ATOMIC`), частичный уникальный индекс-страховка (`V3`), уборщик через `SKIP LOCKED`, `SeatRaceIntegrationTest` на 1000 виртуальных потоков, решение и цифры в [ADR 0001](docs/adr/0001-seat-locking.md). Без индекса NAIVE продал одно место 5–19 раз, JVM-lock на двух инстансах 2 раза. 75 тестов.

**Задачи**
1. Тест на гонку: 1 000 виртуальных потоков, старт по `CountDownLatch`, все бронируют одно место. Наивный код (`check-then-act`) показывает двойную продажу. Фиксируем это в тесте.
2. Сравниваем способы исправления. Каждый оформлен отдельной реализацией `SeatLockingStrategy`, переключается в настройках:

   | Способ | Как | Что покажем |
   |---|---|---|
   | `synchronized` / `ReentrantLock` | блокировка в JVM | работает на одном инстансе, **ломается на двух** (запускаем 2 инстанса) |
   | Оптимистичная | `@Version`, retry при конфликте | хороша при низкой конкуренции, при высокой много ретраев |
   | Пессимистичная | `SELECT ... FOR UPDATE` | надёжно, но потоки ждут друг друга |
   | `FOR UPDATE SKIP LOCKED` | «дай любые N свободных мест в секции» | лучший вариант для автовыбора мест |
   | Атомарный UPDATE | `UPDATE seats SET status='HELD' WHERE id=? AND status='AVAILABLE'` | самый быстрый для конкретного места |

3. Страховка на уровне БД: частичный уникальный индекс `booking_seats(seat_id) WHERE active`. Даже при баге в коде двойная продажа невозможна.
4. Мини-бенчмарк: пропускная способность, число конфликтов и p99 для каждого способа. Результаты в `docs/adr/0001-seat-locking.md`.

**Готово, когда:** тест на гонку стабильно зелёный для всех способов, кроме JVM-блокировки на двух инстансах. Есть таблица сравнения.

**В резюме:** «Сравнил 5 стратегий блокировки мест под 1 000 конкурентных запросов; выбрал атомарный UPDATE + SKIP LOCKED, двойных продаж 0».

---

## Блок 4 · 23.10 · Слои, валидация, API Gateway, идемпотентность ✅

**Сделано:** модуль `api-gateway` (Spring Cloud Gateway на WebFlux, порт 8080) с маршрутом `/api/v1/**`, фильтром `X-Request-Id` и `X-Forwarded-*` (`trusted-proxies`). `Idempotency-Key` на `POST /bookings`: таблица `idempotency_keys` (`V4`), ключ пишется первым в той же транзакции через `INSERT ... ON CONFLICT DO NOTHING`, дубль ждёт на первичном ключе и получает ту же бронь; другое тело с тем же ключом → 422; ключи старше суток удаляет уборщик. `@CurrentUser` через `HandlerMethodArgumentResolver`. Все ошибки в ProblemDetail (`ResponseEntityExceptionHandler`), валидация со списком `errors`. Адрес и пароль БД только в профиле `local`, тесты в профиле `test`. springdoc, Swagger UI. 87 тестов.

**Задачи**
1. Слои controller → service → repository, DTO отдельно от сущностей, маппинг (MapStruct или вручную).
2. Bean Validation на запросах, глобальный `@RestControllerAdvice` с ответами RFC 7807 `ProblemDetail`. `SeatUnavailableException` → 409, валидация → 400, не найдено → 404.
3. Профили `local`, `test`, `docker`. Секреты через переменные окружения.
4. Модуль `api-gateway` (Spring Cloud Gateway): маршруты `/api/v1/**` на сервисы, фильтр, который ставит `X-Request-Id`, если его нет.
5. `Idempotency-Key` на `POST /bookings`: таблица `idempotency_keys` (key, user_id, request_hash, response, created_at). Ключ записывается в той же транзакции, что и бронь. Повтор с тем же ключом возвращает сохранённый ответ, с другим телом → 422.
6. `X-User-Id` через `HandlerMethodArgumentResolver`, без заголовка → 400.
7. OpenAPI (springdoc), Swagger UI.

**Готово, когда:** ошибки понятные, всё ходит через gateway на 8080, повторный POST не создаёт вторую бронь.

**Сервисов:** 2.

---

## Блок 5 · 25.10 · Каталог, индексы, N+1 ✅

**Сделано:** модуль `catalog-service` (порт 8082, база `catalog`): площадки, артисты, 100 000 событий. Частичные индексы `(city, starts_at, id) WHERE status = 'ON_SALE'`, keyset-пагинация с непрозрачным курсором: лента 22.8 → 1.1 мс, страница 3 000 через OFFSET 87 мс против 0.7 мс через keyset. N+1 закрыт JOIN FETCH и `default_batch_fetch_size`: страница из 20 событий за 2 запроса, бронь за 3 (P4). booking-service больше не хранит события (`V5`), проверяет их в каталоге через `RestClient` с таймаутом, до транзакции, со склейкой одновременных запросов. Gateway разводит `/events/**` в каталог и `/events/*/seats` в booking. Решения и цифры в [ADR 0002](docs/adr/0002-catalog-queries.md). Отчёт продаж на `JdbcTemplate` и `tsvector` не делали (поиск будет в Elasticsearch, блок 7б). 101 тест.

**Задачи**
1. Новый модуль `catalog-service` с отдельной базой `catalog`: `venues`, `events`, `performers`, `event_performers`, `price_tiers`. Таблица `events` переезжает сюда из booking-service. В booking остаётся только `event_id` и инвентарь мест.
2. Сгенерировать около 100 тыс. событий, чтобы индексы имели значение.
3. Индексы под реальные запросы (город + дата, статус, полнотекстовый `tsvector` как запасной поиск). Разбор `EXPLAIN (ANALYZE, BUFFERS)` до и после, выводы в ADR.
4. N+1: список событий с площадкой и артистами. Сначала показываем 1 + N запросов в логах (счётчик запросов в тесте), потом чиним через `@EntityGraph` / `JOIN FETCH` и `default_batch_fetch_size`.
5. Keyset-пагинация (`WHERE (starts_at, id) > (?, ?)`) против OFFSET, замер на глубоких страницах.
6. Отчёт продаж в booking-service через `JdbcTemplate`: выручка и заполненность по событию за день (агрегаты и оконные функции).
7. booking-service проверяет событие в catalog через `RestClient`. Этот синхронный вызов в неделю 9 обернём circuit breaker'ом.

**Готово, когда:** каталог отвечает быстро на 100 тыс. событий, план запроса разобран, тест на N+1 зелёный.

**Сервисов:** 3.

---

## Блок 6 · 30.10 · Kafka, outbox, DLQ ✅

**Сделано:** Kafka 4.1 в KRaft и MongoDB в compose, Kafka UI в профиле `tools`. Модуль `booking-contracts`: sealed `BookingEvent` и `PaymentEvent` с records, тип в поле `type`. Transactional outbox в booking-service (`V6`): событие пишется в одной транзакции с бронью, релей забирает пачки через `FOR UPDATE SKIP LOCKED` и удаляет их после `acks=all`. Новый эндпоинт `POST /bookings/{id}/pay`. `payment-service` (порт 8083, база `payment`): задержка и доля отказов в настройках, идемпотентность по уникальному `payment_id`, повтор отдаёт тот же результат с тем же `messageId`. `notification-service` (порт 8084, MongoDB): `_id` документа = `messageId`, дубль не вставится. Идемпотентный consumer в booking через `processed_messages`. `@RetryableTopic` во всех consumer'ах со своими суффиксами retry/DLT, битый JSON сразу в DLT. Вживую: 20 оплат → 19 CONFIRMED и 1 CANCELLED за 3.3 с; Kafka остановлена → брони создаются (201), события ждут в outbox и уходят через 8 с после подъёма брокера. Решения в [ADR 0003](docs/adr/0003-kafka-outbox.md). 115 тестов.

**Задачи**
1. Kafka в compose (KRaft, 1 брокер, heap 512 МБ). Kafka UI в отдельном профиле `tools`.
2. Модуль `booking-contracts`: события `BookingCreated`, `BookingConfirmed`, `BookingCancelled`, `PaymentRequested`, `PaymentSucceeded`, `PaymentFailed` (records, JSON). Топики `booking.events`, `payment.events`, ключ сообщения `bookingId` для порядка внутри брони.
3. Transactional outbox в booking-service: таблица `outbox`, запись в той же транзакции, что и бронь. Релей забирает пачки через `FOR UPDATE SKIP LOCKED` (переиспользуем знания недели 3), несколько инстансов не мешают друг другу.
4. `payment-service` (заглушка): принимает запрос, через случайную задержку публикует результат. Процент ошибок и задержка задаются в настройках.
5. `notification-service`: читает события, «отправляет» уведомление в лог.
6. Идемпотентный consumer: таблица / коллекция `processed_messages` (message_id). Повторная доставка ничего не делает.
7. `@RetryableTopic`: retry-топики с backoff и DLT. Отдельный тест кладёт «ядовитое» сообщение и проверяет, что оно ушло в DLT.

**Готово, когда:** бронь создаёт событие, оплата его подтверждает, уведомление приходит, повторная доставка безопасна.

**Сервисов:** 5.

---

## Блок 7 · 01.11 (Redis) и 06.11 (MongoDB, Elasticsearch)

**Сделано (7а, Redis) ✅:** Redis 7.4 в compose (`maxmemory 192mb`, `volatile-lru`). Режим `REDIS` по умолчанию: claim мест `SET NX` до транзакции, проигравшие получают 409 без соединения с БД, атомарный UPDATE и индекс остаются страховкой, при недоступном Redis бронь работает через одну БД. Холд не перенесён в Redis целиком: статус места остаётся в PostgreSQL, полный холд на Lua — в блоке 8а. Очередь истечения холдов в sorted set с захватом через `ZREM`, обход таблицы раз в 5 минут как запасной. Заполненность зала для цены в кэше на 1 с (`sync`). Карточка события в catalog-service: cache-aside, TTL 10 мин + jitter до минуты, `sync`, метрики hit/miss. Инвалидацию по Kafka не делали: у каталога нет API изменения событий. Цифры: гонка на одно место 558 → 282 мс, вживую 49 из 50 проигравших отсеяны в Redis, холд истекает через 1 с после срока вместо 30, кэш каталога 19/20 попаданий. Решения в [ADR 0004](docs/adr/0004-redis-holds-and-cache.md). 120 тестов.

**Задачи**
1. Redis в compose (`maxmemory 192mb`).
2. **Hold мест в Redis:** `SET hold:{eventId}:{seatId} {bookingId} NX EX 600`. Postgres обновляется только при подтверждении. Брошенные холды очищаются через ZSET `hold-expirations` и задачу-уборщик. На keyspace notifications не полагаемся: Redis их не гарантирует.
3. **Кэш каталога (cache-aside):** карточка события и схема зала, TTL с jitter (чтобы ключи не истекали одновременно). Защита от cache stampede: только один поток идёт в БД, остальные ждут. Инвалидация по событию из Kafka.
4. **MongoDB** в notification-service: документ «история броней пользователя» (CQRS read-модель), собирается из событий Kafka. `GET /api/v1/users/{id}/bookings` читает только из Mongo.
5. **Elasticsearch** в catalog-service: индекс событий с маппингом (русский анализатор, completion для подсказок), синхронизация через Kafka, поиск с опечатками и фильтрами по городу и дате, фасеты.
6. Контрольная точка: прогнать тесты блоков 1–6, поправить хвосты.

**Готово, когда:** hold истекает сам, каталог читается из кэша (видно по hit rate), поиск работает с опечатками, история читается из Mongo.

---

## Блок 8 · 08.11 (очередь) и 13.11 (Docker, CI) · Flash sale, тесты, CI

**Задачи**
1. `waiting-room-service`:
   - `POST /queue/{eventId}/join` → позиция в Redis Sorted Set (score = время входа).
   - `GET /queue/{eventId}/status` → позиция и примерное время ожидания (клиент опрашивает).
   - Планировщик каждую секунду пропускает N первых и выдаёт admission-токен (подписанный HMAC, TTL 10 минут).
   - booking-service без валидного токена на событие в режиме flash sale отвечает 403.
2. Lua-скрипт в Redis: проверить и захолдить несколько мест одним атомарным вызовом (всё или ничего).
3. Backpressure: ограничение одновременных запросов в booking-service (bulkhead / семафор). При перегрузе `503 + Retry-After`, а не рост задержки до таймаута.
4. Testcontainers во всех сервисах (Postgres, Redis, Kafka, Mongo). Общий базовый класс для тестов с переиспользованием контейнеров.
5. Dockerfile для каждого сервиса: многостадийная сборка, слоистый jar, `-XX:MaxRAMPercentage=70`, запуск не от root. Профиль `apps` в compose.
6. `.github/workflows/ci.yml`: build → test → docker build, кэш Maven.
7. k6-сценарий `flash-sale.js`: 10 000 виртуальных пользователей на одно событие.

**Готово, когда:** при 10 000 пользователях очередь держит поток, двойных продаж 0, CI зелёный.

**Сервисов:** 6 (финальное число).

**В резюме:** «Виртуальная очередь и атомарный резерв мест на Redis Lua: 10k одновременных пользователей, p99 < X мс, 0 двойных продаж».

---

## Блок 9 · 15.11 · Rate limiter, circuit breaker, saga

**Задачи**
1. **Rate limiter:**
   - В gateway: token bucket в Redis (`RequestRateLimiter`), ключ — `X-User-Id` или IP, разные лимиты на чтение и на `POST /bookings`.
   - Своя реализация sliding window на Lua как второй вариант, сравнение в ADR.
   - Ответ `429` с заголовками `X-RateLimit-Remaining` и `Retry-After`.
2. **Resilience4j** на синхронных вызовах:
   - booking → payment (`authorize`): timeout, retry только для идемпотентных вызовов (с `Idempotency-Key`), circuit breaker, bulkhead.
   - booking → catalog: circuit breaker с fallback на кэш в Redis.
3. **Saga с оркестратором** в booking-service: таблица `saga_state`. Шаги: hold мест → авторизация оплаты → подтверждение. При ошибке или таймауте компенсация в обратном порядке: отмена оплаты, освобождение мест. Шаги идемпотентны.
4. Chaos-тест: payment-service отвечает с 50% ошибок и задержкой 3 с (Toxiproxy или настройки заглушки). Проверяем, что circuit breaker открывается, места возвращаются, остальная система отвечает быстро.

**Готово, когда:** сбои оплаты не роняют систему, место возвращается, лишние запросы получают 429.

**В резюме:** «Saga с компенсацией и circuit breaker: при 50% отказов платёжки p99 остальных запросов не растёт».

---

## Блок 10 · 20.11 · Observability

**Задачи**
1. Micrometer + Prometheus. Бизнес-метрики: `bookings_created_total`, `seat_conflicts_total`, `holds_expired_total`, `queue_length`, `circuit_breaker_state`, задержка Kafka consumer'ов.
2. Grafana: дашборды как код (provisioning), RPS, p50/p95/p99, ошибки, пул соединений Hikari, состояние circuit breaker, очередь.
3. Логи в JSON (structured logging в Spring Boot), в Loki через loki4j. `X-Request-Id` → MDC → заголовки Kafka → MDC consumer'а: одну бронь видно сквозь все сервисы.
4. По желанию: Micrometer Tracing + OpenTelemetry → Tempo (+150 МБ).
5. k6 baseline: три сценария (просмотр каталога, обычные брони, flash sale), результаты в `load-tests/results/baseline.md`.

**Готово, когда:** в Grafana видны RPS и p99, лог ищется по request id, есть первые цифры.

---

## Блоки 11–12 · 22.11 (нагрузка) и 27.11 (kind, README)

**Задачи**
1. kind (1 control-plane + 1 worker). В кластере только 6 сервисов, инфраструктура остаётся в compose (иначе не влезет в память).
2. Манифесты: Deployment с `resources` (requests/limits), Service, Ingress (ingress-nginx), liveness / readiness / startup пробы через actuator, ConfigMap.
3. HPA по CPU на booking-service (metrics-server), 1 → 3 реплики под нагрузкой k6. Это заодно доказывает, что блокировки работают на нескольких инстансах.
4. Нагрузка: найти узкое место (ожидаемые кандидаты: пул Hikari, горячие строки, размер батча outbox), поправить, замерить до и после.
5. README: схема архитектуры (Mermaid), как запустить, таблица цифр, ссылки на ADR.
6. Рассказ о проекте для собеседования на 3 минуты: проблема → решения → цифры.

**Готово, когда:** проект запускается в kind, в README есть цифры и схема.

---

## Резерв · 29.11

Добить незавершённое, отполировать README и рассказ. Если всё готово: Grafana-скриншоты в README, короткое видео демо flash sale.

---

## Что в итоге будет в резюме

- 6 микросервисов на Java 21 / Spring Boot 4, Kafka, Redis, PostgreSQL, MongoDB, Elasticsearch.
- 0 двойных продаж под 10 000 одновременных пользователей: сравнение 5 стратегий блокировки, атомарный резерв на Redis Lua, виртуальная очередь.
- Transactional outbox, идемпотентные consumer'ы, DLQ, saga с компенсацией.
- Rate limiter (token bucket и sliding window на Redis), circuit breaker, bulkhead, backpressure.
- Prometheus, Grafana, Loki, сквозной request id, нагрузочные тесты k6 с цифрами до и после.
- Docker, GitHub Actions, Kubernetes (kind) с HPA. Всё запускается на ноутбуке с 16 ГБ.

## Риски

| Риск | Что делаем |
|---|---|
| Пятница сорвалась | Блок переезжает на следующий слот, неделя 12 резервная |
| Не хватает памяти | Профили compose, Elasticsearch и obs поднимаются только когда нужны |
| Неделя 11 перегружена (k8s + нагрузка + README) | k8s в минимальном варианте, нагрузку частично начать в неделю 10 |
