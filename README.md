# Booking Platform

High-load бронирование билетов на микросервисах: блокировки, Kafka, Redis, rate limiter, circuit breaker. План и бюджет памяти в [PLAN.md](PLAN.md).

## Запуск

Нужны Java 21 и Docker.

```bash
docker compose up -d --wait
./mvnw -pl booking-service spring-boot:run
```

Postgres доступен на `localhost:5433` (`booking` / `booking`), сервис на `localhost:8081`. В профиле `local` (по умолчанию) накатываются демо-данные: 3 события, у «Flash Sale Mega Concert» 20 000 мест.

```bash
curl localhost:8081/api/v1/events
curl localhost:8081/api/v1/events/2/availability
curl "localhost:8081/api/v1/events/1/seats?status=AVAILABLE&limit=10"
```

## Тесты

```bash
./mvnw test
```

Интеграционные тесты поднимают Postgres через Testcontainers, Docker должен быть запущен.

## Модули

| Модуль | Порт | Что делает |
|---|---|---|
| booking-service | 8081 | События, места, брони |
