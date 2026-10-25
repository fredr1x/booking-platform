-- Места демо-событий для локального запуска и тестов (только профили local и test).
-- Сами события (id 1, 2, 3) лежат в каталоге: catalog-service, db/seed/R__demo_catalog.sql.
-- Repeatable-миграция, вставки идемпотентны за счёт уникальности позиции места.

-- 1. Jazz Night: 5 секций x 20 рядов x 25 мест = 2 500 мест
INSERT INTO seats (event_id, section, row_label, seat_number, price)
SELECT 1, 'S' || s, r::text, n, 15000 + (5 - s) * 2500
FROM generate_series(1, 5) s,
     generate_series(1, 20) r,
     generate_series(1, 25) n
ON CONFLICT ON CONSTRAINT uq_seats_position DO NOTHING;

-- 2. Flash Sale Mega Concert: 10 секций x 50 рядов x 40 мест = 20 000 мест, на нём гоняем нагрузку
INSERT INTO seats (event_id, section, row_label, seat_number, price)
SELECT 2, 'S' || s, r::text, n, 20000 + (10 - s) * 3000
FROM generate_series(1, 10) s,
     generate_series(1, 50) r,
     generate_series(1, 40) n
ON CONFLICT ON CONSTRAINT uq_seats_position DO NOTHING;

-- 3. Stand-up Evening (черновик): 10 рядов x 20 мест = 200 мест
INSERT INTO seats (event_id, section, row_label, seat_number, price)
SELECT 3, 'MAIN', r::text, n, 8000
FROM generate_series(1, 10) r,
     generate_series(1, 20) n
ON CONFLICT ON CONSTRAINT uq_seats_position DO NOTHING;
