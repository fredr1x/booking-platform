# Booking Platform

High-load бронирование билетов на микросервисах: блокировки, Kafka, Redis, rate limiter, circuit breaker. План и бюджет памяти в [PLAN.md](PLAN.md).

## Запуск

Весь проект одной командой, нужен только Docker:

```bash
docker compose --profile apps up -d --build --wait
```

Поднимаются инфраструктура и шесть сервисов в контейнерах (первая сборка около 5 минут, старт около 3). Без `--profile apps` поднимается только инфраструктура, а сервисы запускаются из IDE. Образы и CI описаны в [ADR 0007](docs/adr/0007-docker-images-and-ci.md).

Из IDE нужны Java 21 и Docker:

```bash
docker compose up -d --wait
./mvnw install -DskipTests          # booking-contracts нужен остальным модулям
./mvnw -pl catalog-service spring-boot:run
./mvnw -pl booking-service spring-boot:run
./mvnw -pl payment-service spring-boot:run
./mvnw -pl notification-service spring-boot:run
./mvnw -pl waiting-room-service spring-boot:run
./mvnw -pl api-gateway spring-boot:run
```

Compose поднимает Postgres, Kafka (KRaft, `localhost:9092`), MongoDB (`localhost:27017`), Redis (`localhost:6379`) и Elasticsearch (`localhost:9200`). Kafka UI: `docker compose --profile tools up -d kafka-ui`, затем `localhost:8090`.

Все запросы идут через gateway на `localhost:8080`. Postgres на `localhost:5433` (`booking` / `booking`), у каждого сервиса своя база: `booking`, `catalog` и `payment`. Если том Postgres создан раньше, недостающие базы нужно создать один раз вручную: `docker exec booking-postgres psql -U booking -c "CREATE DATABASE payment"` (и так же `catalog`, если том старше блока 5). В профиле `local` (по умолчанию) накатываются демо-данные: 100 000 событий в каталоге, у «Flash Sale Mega Concert» (id 2) 20 000 мест. Вне профиля `local` адрес и пароль БД задаются переменными `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.

```bash
curl "localhost:8080/api/v1/events?city=City%207&size=20"   # следующая страница: &after=<nextCursor>
curl localhost:8080/api/v1/events/1
curl "localhost:8080/api/v1/events/search?q=jaz%20nigth"     # с опечатками; фильтры &city=, &from=, &to= (ISO-время)
curl localhost:8080/api/v1/events/2/availability
curl "localhost:8080/api/v1/events/1/seats?status=AVAILABLE&limit=10"

# повтор с тем же Idempotency-Key вернёт ту же бронь, а не 409
curl -X POST localhost:8080/api/v1/bookings -H 'Content-Type: application/json'   -H 'X-User-Id: alice' -H 'Idempotency-Key: 7f1c-retry-1' -d '{"eventId":1,"seatIds":[1]}'

# оплата синхронная: ответ приходит через полсекунды, уже CONFIRMED (или CANCELLED, если оплата не прошла)
curl -X POST localhost:8080/api/v1/bookings/1/pay -H 'X-User-Id: alice'
curl localhost:8080/api/v1/bookings/1 -H 'X-User-Id: alice'

# история броней пользователя: читается из MongoDB, чужую не отдаст (403)
curl localhost:8080/api/v1/users/alice/bookings -H 'X-User-Id: alice'
```

**События брони через Kafka:** booking-service пишет событие в таблицу `outbox` в той же транзакции, что и бронь, релей отправляет его в `booking.events`. notification-service пишет уведомление в MongoDB и в лог. Повторная доставка любого сообщения ничего не меняет. Подробности в [ADR 0003](docs/adr/0003-kafka-outbox.md).

**Оплата и сбои:** оплата — сага в booking-service: шаг пишется в `saga_state`, payment-service вызывается синхронно (таймаут 2 с, повтор с тем же `Idempotency-Key`, circuit breaker, bulkhead). Банк отказал или платёжка не ответила — бронь отменяется, места освобождаются сразу, деньги возвращаются компенсацией, неудачный возврат повторяет уборщик. Пока circuit breaker открыт, оплата сразу отвечает `503` и `Retry-After`, бронь не трогается. Каталог недоступен — бронь берёт последнее известное событие из Redis. Gateway ограничивает запросы token bucket'ом в Redis: 50 в секунду на пользователя и 5 в секунду на создание и оплату брони, сверх — `429` и `Retry-After`. Chaos: `PAYMENT_ERROR_RATE=0.5 PAYMENT_DELAY=3s` у payment-service, нагрузка `k6 run load/payment-chaos.js`. Подробности в [ADR 0008](docs/adr/0008-resilience-and-saga.md).

**Redis:** перед транзакцией бронь ставит claim на места (`SET NX`), и из тысячи покупателей одного места до PostgreSQL доходит один. Холды истекают по очереди в sorted set раз в секунду, карточка события и заполненность зала кэшируются. Redis здесь фильтр и кэш: если он недоступен, бронь работает через одну БД. Подробности в [ADR 0004](docs/adr/0004-redis-holds-and-cache.md). Попадания в кэш каталога: `localhost:8082/actuator/metrics/cache.gets?tag=result:hit`.

**История броней и поиск:** notification-service собирает историю броней пользователя из `booking.events` в MongoDB (CQRS read-модель, своя consumer group), история не нагружает БД брони. Каталог на старте индексирует события в Elasticsearch (около минуты на 100 000) и ищет по названию, артистам и площадке с опечатками. Без Elasticsearch каталог работает, не работает только поиск. Подробности в [ADR 0005](docs/adr/0005-mongo-history-and-search.md).

**Старт продаж:** с `FLASH_SALE_EVENTS=2` в booking-service событие бронируется только с пропуском из виртуальной очереди (без него 403). Покупатель входит в очередь (`POST /api/v1/queue/2/join`), опрашивает `GET /api/v1/queue/2/status`, получает `admissionToken` и передаёт его в `X-Admission-Token`. Очередь пропускает 100 человек в секунду. Сверх 100 одновременных броней сервис сразу отвечает `503` и `Retry-After`. Нагрузка: `k6 run -e USERS=10000 -e USERS_PER_VU=50 load/flash-sale.js`. Подробности в [ADR 0006](docs/adr/0006-flash-sale-queue.md).

**Наблюдаемость:** `docker compose --profile apps --profile obs up -d --build --wait` добавляет Prometheus (`localhost:9090`), Loki, Alloy и Grafana (`localhost:3000`, без логина). Дашборд «Booking platform» показывает RPS, p50/p95/p99, ошибки, пул Hikari, circuit breaker'ы, очередь, лаг Kafka и бизнес-метрики: брони, конфликты мест, истечения, оплаты. Логи сервисов в контейнерах — JSON, их собирает Alloy в Loki. Каждый запрос получает `X-Request-Id` в gateway, он идёт через HTTP-вызовы, outbox и заголовок Kafka. Впиши id из ответа в поле сверху дашборда, и панель логов покажет путь брони через все сервисы. Подробности в [ADR 0009](docs/adr/0009-observability.md), первые цифры под нагрузкой в [load/results/baseline.md](load/results/baseline.md).

**Симулятор броней:** `localhost:8080` (страница лежит в `api-gateway/src/main/resources/static`). Выбираешь событие, число покупателей и сценарий: все на одно место (гонка), каждый на своё, или с повтором по `Idempotency-Key`. Страница показывает ответы по кодам, p50/p95/p99, гистограмму задержек, места события в реальном времени и последние брони с отменой. Нагрузку она не создаёт (браузер держит около 6 соединений на хост), для цифр будет k6.

Swagger UI: `localhost:8081/swagger-ui.html` (booking) и `localhost:8082/swagger-ui.html` (catalog).

## Цифры

Всё в Docker на одном ноутбуке (12 логических ядер), k6 там же, сервисы прогреты. Подробности и эксперименты в [load/results/before-after.md](load/results/before-after.md), решения в [ADR 0010](docs/adr/0010-load-and-bottlenecks.md).

| Сценарий | До блока 11 | После |
|---|---|---|
| Брони 100/с (бронь, оплата, отмена): подтверждено в секунду | 30 | **77** |
| Брони 100/с: p99 брони / оплаты | 1.27 с / 2.79 с | **748 мс / 1.66 с** |
| Flash sale, 10 000 покупателей: p99 брони | 4.47 с | **565 мс** |
| Flash sale: двойных продаж | 0 | **0** |
| Просмотр каталога, 400 rps: p99 | 41–72 мс | 27–55 мс |

Что изменили:
- bulkhead платёжки подобран по закону Литтла, у возвратов свой;
- куча — 50% лимита контейнера, booking и catalog по 768 МБ.

Больший пул соединений и асинхронный commit проверили, не помогли. Дальше упираемся в CPU ноутбука.

## Тесты

```bash
./mvnw test
```

Интеграционные тесты поднимают Postgres, Kafka, MongoDB, Redis и Elasticsearch через Testcontainers, Docker должен быть запущен.

## Модули

| Модуль | Порт | Что делает |
|---|---|---|
| api-gateway | 8080 | Единая точка входа, маршруты, `X-Request-Id`, rate limiter в Redis |
| catalog-service | 8082 | События, площадки, артисты, лента с keyset-пагинацией, кэш карточек в Redis, поиск в Elasticsearch |
| booking-service | 8081 | Места, брони, `Idempotency-Key`, оплата; события проверяет в каталоге; outbox в Kafka; claim мест и истечение холдов в Redis |
| payment-service | 8083 | Заглушка платёжки: синхронные authorize и refund, идемпотентные по paymentId, настраиваемые отказы и задержка |
| notification-service | 8084 | Уведомления по событиям брони и история броней пользователя (read-модель), MongoDB |
| waiting-room-service | 8085 | Виртуальная очередь на старт продаж, пропуски с HMAC-подписью, Redis |
| booking-contracts | — | События Kafka (sealed-интерфейсы и records) и имена топиков |
