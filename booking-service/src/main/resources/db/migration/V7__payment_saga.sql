-- Блок 9: оплата синхронная (REST), результат из payment.events больше не приходит,
-- поэтому отметки обработанных сообщений не нужны.
DROP TABLE processed_messages;

-- Состояние саги оплаты: одна строка на попытку оплатить бронь. Шаги идут вне транзакции (HTTP к платёжке),
-- поэтому после падения сервиса по этой таблице видно, на каком шаге остановились, и уборщик доводит сагу.
CREATE TABLE saga_state (
    booking_id  BIGINT      PRIMARY KEY REFERENCES bookings (id),
    payment_id  VARCHAR(64) NOT NULL,
    -- AUTHORIZING → COMPLETED, или AUTHORIZING → COMPENSATING → COMPENSATED
    status      VARCHAR(20) NOT NULL,
    -- сколько раз пытались вернуть деньги
    attempts    INT         NOT NULL DEFAULT 0,
    last_error  VARCHAR(500),
    updated_at  TIMESTAMPTZ NOT NULL
);

-- уборщик ищет только незавершённые саги
CREATE INDEX idx_saga_state_unfinished ON saga_state (updated_at) WHERE status IN ('AUTHORIZING', 'COMPENSATING');
