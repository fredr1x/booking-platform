# ADR 0007: Docker-образы сервисов, профиль apps в compose, CI в GitHub Actions

**Статус:** принято, 13.11.2026 (блок 8б)

## Контекст

Сервисы запускались только из IDE: шесть терминалов с `spring-boot:run`, и проект на чужой машине не поднимался одной командой. Тесты гонялись только локально, сломанный `main` никто бы не заметил. Здоровье каталога падало в DOWN без Elasticsearch (P24), хотя без него не работает только поиск.

## Решения

### 1. Один Dockerfile на все сервисы

Модуль выбирает аргумент `MODULE`. Шесть почти одинаковых файлов пришлось бы править шесть раз.

- **Многостадийная сборка.** Стадия `build` (`maven:3.9-eclipse-temurin-21-alpine`) собирает весь проект. От `MODULE` она не зависит, поэтому BuildKit собирает Maven один раз на все шесть образов. Каталог `~/.m2` в cache mount: при правке кода зависимости не качаются заново.
- **Слоистый jar.** `java -Djarmode=tools -jar app.jar extract --layers --launcher` раскладывает jar на слои: `dependencies` (около 99 МБ), `spring-boot-loader`, `snapshot-dependencies` (`booking-contracts`), `application` (меньше 1 МБ). При правке кода меняется только последний слой: при пуше и деплое уходит меньше мегабайта, а не 100.
- **Рантайм на JRE, не на JDK.** `eclipse-temurin:21-jre-alpine`, образы 360–480 МБ.
- **Не root.** Пользователь `app` (uid 100). Уязвимость в приложении не даст root внутри контейнера.
- **Память по лимиту контейнера.** `-XX:MaxRAMPercentage=75`: куча берётся от `mem_limit` (512 МБ → 384 МБ), а не от памяти хоста. Оставшиеся 25% нужны под metaspace, стеки, буферы Netty и Kafka. `-XX:+ExitOnOutOfMemoryError`: после OOM процесс падает, и `restart: on-failure` его поднимает. Без флага сервис продолжал бы работать в полумёртвом состоянии.

### 2. Профиль apps в compose

`docker compose --profile apps up -d --build --wait` поднимает инфраструктуру и шесть сервисов. Без профиля поднимается только инфраструктура, как раньше, для запуска из IDE.

- Сервисы работают в профиле Spring `docker`. Блок `local` с адресами `localhost` не активен, адреса задают переменные (`SPRING_DATASOURCE_URL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:19092` и т.д.). Демо-данные включены явно через `SPRING_FLYWAY_LOCATIONS`.
- `depends_on` с `condition: service_healthy`: сервис стартует, когда его база и брокер готовы. Gateway стартует последним.
- **Healthcheck по readiness, а не по общему health.** `/actuator/health/readiness` отвечает «готов принимать трафик», а общий health включает каждую зависимость. Без Elasticsearch общий health каталога — 503, readiness — 200, контейнер healthy, события отдаются. Это закрывает P24.
- Секрет пропуска очереди по умолчанию локальный, переопределяется `ADMISSION_TOKEN_SECRET` из окружения или `.env`.

### 3. CI в GitHub Actions

`.github/workflows/ci.yml` на каждый пуш и PR в `main`: `./mvnw -B verify` (все тесты с Testcontainers, Docker в раннере есть), затем `docker compose --profile apps build`. Кэш Maven через `setup-java`. Новый пуш в ветку отменяет старую сборку. При падении тестов отчёты surefire сохраняются артефактом.

Образы только собираются: реестра и деплоя пока нет. Docker build в CI ловит сломанный Dockerfile до мержа.

### Testcontainers

Интеграционные тесты с Testcontainers уже есть во всех сервисах, у которых есть инфраструктура: booking (Postgres, Kafka, Redis), catalog (Postgres, Redis, Elasticsearch), payment (Postgres, Kafka), notification (MongoDB, Kafka), waiting-room (Redis). Контейнеры статические, один набор на модуль. У gateway инфраструктуры нет до rate limiter в Redis (блок 9).

## Замеры

Ноутбук, 16 ГБ, Docker Desktop на WSL2.

| Что | Время |
|---|---|
| Сборка шести образов с нуля | 4 мин 30 с |
| `up --wait` до healthy всех 11 контейнеров | 2 мин 40 с (каталог индексирует 100 000 событий) |

Память сервисов после старта и сквозного сценария при лимите 512 МБ: booking 405 МБ, catalog 341, payment 299, notification 257, waiting-room 182, gateway 182.

## Последствия

- booking-service занимает 80% лимита уже в покое. Под нагрузкой k6 его стоит поднять до 768 МБ, проверка в блоке 11.
- Порты сервисов те же, что у запуска из IDE: одновременно оба способа не работают.
- `docker compose build` в CI собирает образы без кэша слоёв между запусками. Пока это минута-две, кэш GHA добавим, если станет дольше.
- Поиск без Elasticsearch отвечает 500, а должен 503.
