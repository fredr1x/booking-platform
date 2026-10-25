CREATE TABLE venues (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    city VARCHAR(100) NOT NULL
);

CREATE TABLE performers (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(200) NOT NULL
);

CREATE TABLE events (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title         VARCHAR(200) NOT NULL,
    venue_id      BIGINT       NOT NULL REFERENCES venues (id),
    -- копия venues.city: фильтр «город + дата» и сортировка по дате идут по одному индексу без JOIN
    city          VARCHAR(100) NOT NULL,
    starts_at     TIMESTAMPTZ  NOT NULL,
    sales_open_at TIMESTAMPTZ,
    status        VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_events_status CHECK (status IN ('DRAFT', 'ON_SALE', 'SOLD_OUT', 'CLOSED'))
);

CREATE TABLE event_performers (
    event_id     BIGINT NOT NULL REFERENCES events (id),
    performer_id BIGINT NOT NULL REFERENCES performers (id),
    PRIMARY KEY (event_id, performer_id)
);
