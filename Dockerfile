# Один Dockerfile на все сервисы: модуль выбирает аргумент MODULE (docker compose --profile apps build).
# Стадия build не зависит от MODULE, поэтому BuildKit собирает Maven-проект один раз на все шесть образов.

FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY . .
# кэш ~/.m2 переживает пересборку: зависимости не качаются заново при правке кода
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

FROM build AS extract
ARG MODULE
# слоистый jar: зависимости, загрузчик, SNAPSHOT-модули и код приложения ложатся отдельными слоями образа
RUN java -Djarmode=tools -jar ${MODULE}/target/${MODULE}-*.jar extract --layers --launcher --destination /layers

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
# от редко меняющегося к часто меняющемуся: при правке кода пересобирается только последний слой
COPY --from=extract /layers/dependencies/ ./
COPY --from=extract /layers/spring-boot-loader/ ./
COPY --from=extract /layers/snapshot-dependencies/ ./
COPY --from=extract /layers/application/ ./
USER app
# куча — доля от лимита контейнера, а не от памяти хоста; при OOM процесс падает, и Docker его перезапускает
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
