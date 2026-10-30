-- Платежи заглушки. Строка пишется один раз и больше не меняется.
CREATE TABLE payments (
    id                BIGSERIAL     PRIMARY KEY,
    -- выдаёт booking-service; уникальность защищает от второго списания при повторной доставке запроса
    payment_id        VARCHAR(64)   NOT NULL UNIQUE,
    booking_id        BIGINT        NOT NULL,
    user_id           VARCHAR(64)   NOT NULL,
    amount            NUMERIC(12, 2) NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    failure_reason    VARCHAR(255),
    result_message_id UUID          NOT NULL,
    processed_at      TIMESTAMPTZ   NOT NULL
);
