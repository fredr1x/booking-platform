-- Демо-данные для локального запуска и нагрузочных тестов (только профиль local).
-- Repeatable-миграция: выполняется после всех версионных, поэтому вставки идемпотентны.

-- По одному INSERT на событие: так на чистой базе id стабильны (1, 2, 3), на них опираются README и тесты.
INSERT INTO events (title, venue, starts_at, sales_open_at, status)
SELECT 'Jazz Night', 'Almaty Arena', now() + interval '30 days', now() - interval '1 day', 'ON_SALE'
WHERE NOT EXISTS (SELECT 1 FROM events WHERE title = 'Jazz Night');

INSERT INTO events (title, venue, starts_at, sales_open_at, status)
SELECT 'Flash Sale Mega Concert', 'Central Stadium', now() + interval '60 days', now() - interval '1 hour', 'ON_SALE'
WHERE NOT EXISTS (SELECT 1 FROM events WHERE title = 'Flash Sale Mega Concert');

INSERT INTO events (title, venue, starts_at, sales_open_at, status)
SELECT 'Stand-up Evening', 'Small Hall', now() + interval '14 days', NULL, 'DRAFT'
WHERE NOT EXISTS (SELECT 1 FROM events WHERE title = 'Stand-up Evening');

-- Jazz Night: 5 секций x 20 рядов x 25 мест = 2 500 мест
INSERT INTO seats (event_id, section, row_label, seat_number, price)
SELECT e.id, 'S' || s, r::text, n, 15000 + (5 - s) * 2500
FROM events e,
     generate_series(1, 5) s,
     generate_series(1, 20) r,
     generate_series(1, 25) n
WHERE e.title = 'Jazz Night'
ON CONFLICT ON CONSTRAINT uq_seats_position DO NOTHING;

-- Flash Sale: 10 секций x 50 рядов x 40 мест = 20 000 мест, на нём гоняем нагрузку
INSERT INTO seats (event_id, section, row_label, seat_number, price)
SELECT e.id, 'S' || s, r::text, n, 20000 + (10 - s) * 3000
FROM events e,
     generate_series(1, 10) s,
     generate_series(1, 50) r,
     generate_series(1, 40) n
WHERE e.title = 'Flash Sale Mega Concert'
ON CONFLICT ON CONSTRAINT uq_seats_position DO NOTHING;

-- Stand-up: 10 рядов x 20 мест = 200 мест
INSERT INTO seats (event_id, section, row_label, seat_number, price)
SELECT e.id, 'MAIN', r::text, n, 8000
FROM events e,
     generate_series(1, 10) r,
     generate_series(1, 20) n
WHERE e.title = 'Stand-up Evening'
ON CONFLICT ON CONSTRAINT uq_seats_position DO NOTHING;
