# Booking Platform

High-load бронирование билетов на микросервисах: блокировки, Kafka, Redis, rate limiter, circuit breaker. План и бюджет памяти в [PLAN.md](PLAN.md).

## Запуск

Нужны Java 21 и Docker.

```bash
docker compose up -d --wait
./mvnw -pl catalog-service spring-boot:run
./mvnw -pl booking-service spring-boot:run
./mvnw -pl api-gateway spring-boot:run
```

Все запросы идут через gateway на `localhost:8080`. Postgres на `localhost:5433` (`booking` / `booking`), у каждого сервиса своя база: `booking` и `catalog`. Если том Postgres создан до блока 5, базу каталога нужно создать один раз вручную: `docker exec booking-postgres psql -U booking -c "CREATE DATABASE catalog"`. В профиле `local` (по умолчанию) накатываются демо-данные: 100 000 событий в каталоге, у «Flash Sale Mega Concert» (id 2) 20 000 мест. Вне профиля `local` адрес и пароль БД задаются переменными `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.

```bash
curl "localhost:8080/api/v1/events?city=City%207&size=20"   # следующая страница: &after=<nextCursor>
curl localhost:8080/api/v1/events/1
curl localhost:8080/api/v1/events/2/availability
curl "localhost:8080/api/v1/events/1/seats?status=AVAILABLE&limit=10"

# повтор с тем же Idempotency-Key вернёт ту же бронь, а не 409
curl -X POST localhost:8080/api/v1/bookings -H 'Content-Type: application/json'   -H 'X-User-Id: alice' -H 'Idempotency-Key: 7f1c-retry-1' -d '{"eventId":1,"seatIds":[1]}'
```

**Симулятор броней:** `localhost:8080` (страница лежит в `api-gateway/src/main/resources/static`). Выбираешь событие, число покупателей и сценарий: все на одно место (гонка), каждый на своё, или с повтором по `Idempotency-Key`. Страница показывает ответы по кодам, p50/p95/p99, гистограмму задержек, места события в реальном времени и последние брони с отменой. Нагрузку она не создаёт (браузер держит около 6 соединений на хост), для цифр будет k6.

Swagger UI: `localhost:8081/swagger-ui.html` (booking) и `localhost:8082/swagger-ui.html` (catalog).

## Тесты

```bash
./mvnw test
```

Интеграционные тесты поднимают Postgres через Testcontainers, Docker должен быть запущен.

## Модули

| Модуль | Порт | Что делает |
|---|---|---|
| api-gateway | 8080 | Единая точка входа, маршруты, `X-Request-Id` |
| catalog-service | 8082 | События, площадки, артисты, лента с keyset-пагинацией |
| booking-service | 8081 | Места, брони, `Idempotency-Key`; события проверяет в каталоге |
