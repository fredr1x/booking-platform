# Booking Platform

High-load бронирование билетов на микросервисах: блокировки, Kafka, Redis, rate limiter, circuit breaker. План и бюджет памяти в [PLAN.md](PLAN.md).

## Запуск

Нужны Java 21 и Docker.

```bash
docker compose up -d --wait
./mvnw install -DskipTests          # booking-contracts нужен остальным модулям
./mvnw -pl catalog-service spring-boot:run
./mvnw -pl booking-service spring-boot:run
./mvnw -pl payment-service spring-boot:run
./mvnw -pl notification-service spring-boot:run
./mvnw -pl api-gateway spring-boot:run
```

Compose поднимает Postgres, Kafka (KRaft, `localhost:9092`), MongoDB (`localhost:27017`) и Redis (`localhost:6379`). Kafka UI: `docker compose --profile tools up -d kafka-ui`, затем `localhost:8090`.

Все запросы идут через gateway на `localhost:8080`. Postgres на `localhost:5433` (`booking` / `booking`), у каждого сервиса своя база: `booking`, `catalog` и `payment`. Если том Postgres создан раньше, недостающие базы нужно создать один раз вручную: `docker exec booking-postgres psql -U booking -c "CREATE DATABASE payment"` (и так же `catalog`, если том старше блока 5). В профиле `local` (по умолчанию) накатываются демо-данные: 100 000 событий в каталоге, у «Flash Sale Mega Concert» (id 2) 20 000 мест. Вне профиля `local` адрес и пароль БД задаются переменными `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.

```bash
curl "localhost:8080/api/v1/events?city=City%207&size=20"   # следующая страница: &after=<nextCursor>
curl localhost:8080/api/v1/events/1
curl localhost:8080/api/v1/events/2/availability
curl "localhost:8080/api/v1/events/1/seats?status=AVAILABLE&limit=10"

# повтор с тем же Idempotency-Key вернёт ту же бронь, а не 409
curl -X POST localhost:8080/api/v1/bookings -H 'Content-Type: application/json'   -H 'X-User-Id: alice' -H 'Idempotency-Key: 7f1c-retry-1' -d '{"eventId":1,"seatIds":[1]}'

# оплата: бронь переходит в AWAITING_PAYMENT, результат приходит через Kafka, через полсекунды CONFIRMED
curl -X POST localhost:8080/api/v1/bookings/1/pay -H 'X-User-Id: alice'
curl localhost:8080/api/v1/bookings/1 -H 'X-User-Id: alice'
```

**Путь брони через Kafka:** booking-service пишет событие в таблицу `outbox` в той же транзакции, что и бронь, релей отправляет его в `booking.events`. payment-service отвечает в `payment.events` (задержка `payment.delay`, доля отказов `payment.failure-rate`), booking-service подтверждает или отменяет бронь, notification-service пишет уведомление в MongoDB и в лог. Повторная доставка любого сообщения ничего не меняет. Подробности в [ADR 0003](docs/adr/0003-kafka-outbox.md).

**Redis:** перед транзакцией бронь ставит claim на места (`SET NX`), и из тысячи покупателей одного места до PostgreSQL доходит один. Холды истекают по очереди в sorted set раз в секунду, карточка события и заполненность зала кэшируются. Redis здесь фильтр и кэш: если он недоступен, бронь работает через одну БД. Подробности в [ADR 0004](docs/adr/0004-redis-holds-and-cache.md). Попадания в кэш каталога: `localhost:8082/actuator/metrics/cache.gets?tag=result:hit`.

**Симулятор броней:** `localhost:8080` (страница лежит в `api-gateway/src/main/resources/static`). Выбираешь событие, число покупателей и сценарий: все на одно место (гонка), каждый на своё, или с повтором по `Idempotency-Key`. Страница показывает ответы по кодам, p50/p95/p99, гистограмму задержек, места события в реальном времени и последние брони с отменой. Нагрузку она не создаёт (браузер держит около 6 соединений на хост), для цифр будет k6.

Swagger UI: `localhost:8081/swagger-ui.html` (booking) и `localhost:8082/swagger-ui.html` (catalog).

## Тесты

```bash
./mvnw test
```

Интеграционные тесты поднимают Postgres, Kafka, MongoDB и Redis через Testcontainers, Docker должен быть запущен.

## Модули

| Модуль | Порт | Что делает |
|---|---|---|
| api-gateway | 8080 | Единая точка входа, маршруты, `X-Request-Id` |
| catalog-service | 8082 | События, площадки, артисты, лента с keyset-пагинацией, кэш карточек в Redis |
| booking-service | 8081 | Места, брони, `Idempotency-Key`, оплата; события проверяет в каталоге; outbox в Kafka; claim мест и истечение холдов в Redis |
| payment-service | 8083 | Заглушка платёжки: читает запросы оплаты из Kafka, отвечает результатом |
| notification-service | 8084 | Уведомления по событиям брони, MongoDB |
| booking-contracts | — | События Kafka (sealed-интерфейсы и records) и имена топиков |
