# Booking Platform

High-load бронирование билетов на микросервисах: блокировки, Kafka, Redis, rate limiter, circuit breaker. План и бюджет памяти в [PLAN.md](PLAN.md).

## Запуск

Нужны Java 21 и Docker.

```bash
docker compose up -d --wait
./mvnw -pl booking-service spring-boot:run
./mvnw -pl api-gateway spring-boot:run
```

Все запросы идут через gateway на `localhost:8080`. Postgres на `localhost:5433` (`booking` / `booking`). В профиле `local` (по умолчанию) накатываются демо-данные: 3 события, у «Flash Sale Mega Concert» 20 000 мест. Вне профиля `local` адрес и пароль БД задаются переменными `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.

```bash
curl localhost:8080/api/v1/events
curl localhost:8080/api/v1/events/2/availability
curl "localhost:8080/api/v1/events/1/seats?status=AVAILABLE&limit=10"

# повтор с тем же Idempotency-Key вернёт ту же бронь, а не 409
curl -X POST localhost:8080/api/v1/bookings -H 'Content-Type: application/json'   -H 'X-User-Id: alice' -H 'Idempotency-Key: 7f1c-retry-1' -d '{"eventId":1,"seatIds":[1]}'
```

Swagger UI: `localhost:8081/swagger-ui.html`.

## Тесты

```bash
./mvnw test
```

Интеграционные тесты поднимают Postgres через Testcontainers, Docker должен быть запущен.

## Модули

| Модуль | Порт | Что делает |
|---|---|---|
| api-gateway | 8080 | Единая точка входа, маршруты, `X-Request-Id` |
| booking-service | 8081 | События, места, брони, `Idempotency-Key` |
