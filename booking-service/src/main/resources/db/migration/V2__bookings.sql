CREATE TABLE bookings (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      BIGINT         NOT NULL REFERENCES events (id),
    user_id       VARCHAR(64)    NOT NULL,
    status        VARCHAR(20)    NOT NULL,
    pricing_type  VARCHAR(20)    NOT NULL,
    total_price   NUMERIC(12, 2) NOT NULL,
    -- данные состояний: какие колонки заполнены, зависит от статуса
    expires_at    TIMESTAMPTZ,
    payment_id    VARCHAR(64),
    confirmed_at  TIMESTAMPTZ,
    cancel_reason VARCHAR(30),
    closed_at     TIMESTAMPTZ,
    version       BIGINT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ    NOT NULL,
    updated_at    TIMESTAMPTZ    NOT NULL,
    CONSTRAINT chk_bookings_status
        CHECK (status IN ('PENDING', 'AWAITING_PAYMENT', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT chk_bookings_pricing_type
        CHECK (pricing_type IN ('STANDARD', 'EARLY_BIRD', 'DEMAND')),
    CONSTRAINT chk_bookings_total_price CHECK (total_price >= 0),
    CONSTRAINT chk_bookings_pending_expires CHECK (status <> 'PENDING' OR expires_at IS NOT NULL)
);

-- история броней пользователя
CREATE INDEX idx_bookings_user ON bookings (user_id, created_at DESC);
-- уборщик просроченных холдов читает только PENDING, частичный индекс остаётся маленьким
CREATE INDEX idx_bookings_pending_expires ON bookings (expires_at) WHERE status = 'PENDING';

CREATE TABLE booking_seats (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    booking_id BIGINT         NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    seat_id    BIGINT         NOT NULL REFERENCES seats (id),
    price      NUMERIC(10, 2) NOT NULL,
    CONSTRAINT uq_booking_seats UNIQUE (booking_id, seat_id),
    CONSTRAINT chk_booking_seats_price CHECK (price >= 0)
);

CREATE INDEX idx_booking_seats_seat ON booking_seats (seat_id);
