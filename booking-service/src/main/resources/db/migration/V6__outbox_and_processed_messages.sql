-- Transactional outbox. Событие пишется в эту таблицу в той же транзакции, что и изменение брони:
-- либо сохранилось и то и другое, либо ничего. В Kafka его отправляет релей (OutboxService.relayBatch)
-- и удаляет строку только после подтверждения от брокера. Отправка повторяется при сбое,
-- поэтому доставка at-least-once, а дубли отсекает consumer по message_id.
CREATE TABLE outbox (
    -- порядок отправки: события одной брони уходят в том порядке, в каком произошли
    id          BIGSERIAL    PRIMARY KEY,
    message_id  UUID         NOT NULL UNIQUE,
    topic       VARCHAR(100) NOT NULL,
    -- ключ сообщения Kafka (bookingId): все события брони в одной партиции
    message_key VARCHAR(100) NOT NULL,
    payload     JSONB        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);

-- Идемпотентный consumer: id обработанных сообщений из payment.events. Запись в той же транзакции,
-- что и смена статуса брони, поэтому повторная доставка того же сообщения ничего не меняет.
CREATE TABLE processed_messages (
    message_id   UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

-- уборщик удаляет записи старше срока хранения сообщений в Kafka: такие уже не придут повторно
CREATE INDEX idx_processed_messages_processed_at ON processed_messages (processed_at);
