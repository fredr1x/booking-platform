CREATE TABLE events (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title         VARCHAR(200) NOT NULL,
    venue         VARCHAR(200) NOT NULL,
    starts_at     TIMESTAMPTZ  NOT NULL,
    sales_open_at TIMESTAMPTZ,
    status        VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_events_status CHECK (status IN ('DRAFT', 'ON_SALE', 'SOLD_OUT', 'CLOSED'))
);

CREATE TABLE seats (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id    BIGINT        NOT NULL REFERENCES events (id),
    section     VARCHAR(20)   NOT NULL,
    row_label   VARCHAR(10)   NOT NULL,
    seat_number INT           NOT NULL,
    price       NUMERIC(10, 2) NOT NULL,
    status      VARCHAR(20)   NOT NULL DEFAULT 'AVAILABLE',
    -- счётчик для оптимистичной блокировки (блок 3)
    version     BIGINT        NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_seats_position UNIQUE (event_id, section, row_label, seat_number),
    CONSTRAINT chk_seats_status CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
    CONSTRAINT chk_seats_price CHECK (price >= 0)
);

-- основной горячий запрос: свободные места события
CREATE INDEX idx_seats_event_status ON seats (event_id, status);
