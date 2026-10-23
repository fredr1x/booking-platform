-- Повтор POST /bookings с тем же Idempotency-Key возвращает уже созданную бронь, а не создаёт вторую.
-- Ключ уникален в рамках пользователя: чужой ключ не пересекается с моим и не раскрывает мою бронь.
CREATE TABLE idempotency_keys (
    user_id         VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    -- SHA-256 тела запроса: тот же ключ с другим телом это ошибка клиента (422), а не повтор
    request_hash    VARCHAR(64)  NOT NULL,
    -- NULL только внутри транзакции, которая создаёт бронь; после коммита всегда заполнен
    booking_id      BIGINT REFERENCES bookings (id),
    created_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (user_id, idempotency_key)
);

-- уборщик удаляет ключи старше суток
CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys (created_at);
