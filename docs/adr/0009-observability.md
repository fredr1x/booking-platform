# ADR 0009: Метрики в Prometheus, JSON-логи в Loki, сквозной request id

**Статус:** принято, 20.11.2026 (блок 10)

## Контекст

О работе системы можно было узнать только из логов в терминале и `/actuator/metrics` по одной метрике. Под нагрузкой не было видно ни RPS, ни p99, ни где теряется время. Одна бронь проходит через gateway, booking, payment и через Kafka в notification, и её путь по логам нельзя было собрать: строки разных сервисов ничем не связаны.

## Решения

### 1. Метрики: Micrometer → Prometheus

- Во всех шести сервисах `micrometer-registry-prometheus` и эндпоинт `/actuator/prometheus`. Prometheus опрашивает их раз в 5 с по именам контейнеров.
- Для `http.server.requests` включены бакеты гистограммы (`percentiles-histogram`). p95 и p99 считает Prometheus (`histogram_quantile`), поэтому их можно сложить по нескольким инстансам: перцентили, посчитанные в самом сервисе, складывать нельзя.
- Бизнес-метрики booking-service (`BookingMetrics`):
  - `bookings_placed_total`;
  - `seat_conflicts_total{caught_by}`: `application` — обычный проигрыш гонки, `database` — гонку пропустил код и поймал уникальный индекс, должно быть около нуля;
  - `holds_expired_total`;
  - `payments_total{result}`: succeeded, declined, unavailable;
  - `circuit_breaker_state{name}`: 0 закрыт, 1 открыт, 2 полуоткрыт; `circuit_breaker_failure_rate_percent`.
- `queue_length{event}` в waiting-room: `ZCARD` при каждом опросе, O(1). Redis недоступен — `NaN`, а не ошибка всего эндпоинта.
- Пул Hikari, JVM и лаг Kafka consumer'ов (`kafka_consumer_fetch_manager_records_lag_max`) Spring Boot отдаёт сам.

Счётчик назван `bookings.placed`, а не `bookings.created`: клиент Prometheus срезает суффикс `_created`, в OpenMetrics он зарезервирован под время создания счётчика, и метрика превращалась в `bookings_total`. Нашли на живом прогоне.

Breaker отдаётся двумя своими gauge, а не через `resilience4j-micrometer`: модулю нужен `CircuitBreakerRegistry`, а breaker'ы созданы через `CircuitBreaker.of` в клиентах.

### 2. Логи: JSON в stdout → Alloy → Loki

- В профиле docker сервисы пишут логи в формате ECS (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, structured logging из Spring Boot). Из IDE логи остаются обычным текстом: читать JSON в терминале неудобно.
- Grafana Alloy читает stdout контейнеров проекта через Docker API и отправляет в Loki с меткой `service`. Поля (`requestId`, уровень) Loki разбирает при запросе: `{service="booking-service"} | json | requestId="..."`.
- Почему не loki4j (appender в каждом сервисе, как было в плане):
  - сервису не нужно знать про Loki: он пишет в stdout, как требует 12-factor;
  - не нужен `logback-spring.xml` в шести модулях;
  - тот же подход в Kubernetes (блок 12): агент на узле читает логи подов.
  - Минус: логи сервиса из IDE в Loki не попадают.
- Метки Loki только `service` и `container`: `requestId` в метке дал бы по потоку на запрос, и индекс Loki разросся бы.

### 3. Сквозной request id

```
клиент → gateway: X-Request-Id (свой или UUID)
  → сервис: RequestIdFilter → MDC requestId → поле requestId в каждой строке JSON-лога
  → RestClient (catalog, payment): RequestIdFilter.propagate() кладёт заголовок из MDC
  → outbox: колонка request_id (V8) → релей → заголовок Kafka-сообщения X-Request-Id
  → notification-service: RecordInterceptor → MDC на время обработки сообщения
```

- `RequestIdFilter` лежит в `booking-contracts` и подключается через `@Import`: один класс на пять MVC-сервисов. Чужой id проверяется тем же правилом, что и в gateway (`[A-Za-z0-9-]{1,64}`): сервисы доступны и мимо gateway, а id попадает в логи.
- Через Kafka id идёт заголовком, а не полем события: это метаданные доставки, контракт события не меняется.
- Релей работает в планировщике, без запроса, поэтому id сохраняется в строку outbox в момент записи события. У событий от планировщиков (истечение холда, уборщик саг) id нет.
- В ответ id пишет только gateway. Сначала его писал и сервис, и gateway пробрасывал заголовок из ответа: в ответе было два `X-Request-Id`.

Почему не Micrometer Tracing с traceId: outbox всё равно рвёт трассу (отправка в Kafka идёт из планировщика), и traceparent пришлось бы так же хранить в outbox. Свой request id проще, а трассировку в Tempo оставили на потом.

### 4. Grafana как код

Источники данных и дашборд лежат в `deploy/compose/grafana` и подключаются provisioning'ом. Правки в UI выключены (`allowUiUpdates: false`): дашборд меняется только в git. Дашборд «Booking platform»:
- RPS и p99 по сервисам, p50/p95/p99 `POST /bookings`;
- 5xx и 429;
- брони, конфликты мест, истечения холдов, оплаты;
- пул Hikari;
- состояние circuit breaker'ов на шкале времени;
- длина очереди и лаг Kafka;
- логи с поиском по `X-Request-Id`.

Prometheus, Loki, Alloy и Grafana в профиле `obs`: `docker compose --profile apps --profile obs up -d`. Под нагрузкой заняли около 320 МБ.

## Проверка

- Бронь с `X-Request-Id: demo-1795155374`: по этому id в Loki пять строк из трёх сервисов. booking-service — бронь создана и оплата прошла, payment-service — платёж, notification-service — два уведомления, пришедшие через Kafka.
- Все запросы дашборда возвращают данные во время flash sale.
- Тесты:
  - `RequestIdFilterTest`;
  - заголовок Kafka в `BookingMessagingIntegrationTest`;
  - счётчики броней и конфликтов и gauge breaker'а в `BookingApiIntegrationTest`.

Первые цифры под нагрузкой — в [load/results/baseline.md](../../load/results/baseline.md). Главное:
- gateway добавляет около 300 мс к медиане просмотра каталога;
- bulkhead платёжки тесен при 20 оплатах в секунду;
- flash sale на 10 000 покупателей прошёл без двойных продаж, p99 брони 1.17 с.

## Последствия

- `/actuator/prometheus` gateway открыт на публичном порту 8080. В проде actuator выносят на отдельный `management.server.port`.
- Alloy читает `docker.sock`, это доступ уровня root к Docker. Только для локального стенда.
- В логах gateway нет `requestId`: gateway реактивный, MDC в Reactor требует context propagation, а сам gateway почти ничего не пишет на запрос.
- Сервис из IDE Prometheus не видит: цели заданы именами контейнеров.
- Раз на прогоне в Loki пришла строка payment-service с испорченным JSON (`"name_extracted-service"` вместо `"name":"payment-service"`). Контейнер к тому времени был пересоздан, его лог пропал, воспроизвести не удалось.
