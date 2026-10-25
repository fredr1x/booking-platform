-- Демо-данные каталога (профили local и test). Repeatable-миграция, все вставки идемпотентны.

-- Три демо-события первыми: на чистой базе у них id 1, 2, 3, на них опираются места в booking-service.
INSERT INTO venues (name, city)
SELECT v.name, v.city
FROM (VALUES ('Almaty Arena', 'Almaty'), ('Central Stadium', 'Almaty'), ('Small Hall', 'Astana')) v(name, city)
WHERE NOT EXISTS (SELECT 1 FROM venues);

INSERT INTO events (title, venue_id, city, starts_at, sales_open_at, status)
SELECT 'Jazz Night', 1, 'Almaty', now() + interval '30 days', now() - interval '1 day', 'ON_SALE'
WHERE NOT EXISTS (SELECT 1 FROM events WHERE id = 1);

INSERT INTO events (title, venue_id, city, starts_at, sales_open_at, status)
SELECT 'Flash Sale Mega Concert', 2, 'Almaty', now() + interval '60 days', now() - interval '1 hour', 'ON_SALE'
WHERE NOT EXISTS (SELECT 1 FROM events WHERE id = 2);

INSERT INTO events (title, venue_id, city, starts_at, sales_open_at, status)
SELECT 'Stand-up Evening', 3, 'Astana', now() + interval '14 days', NULL, 'DRAFT'
WHERE NOT EXISTS (SELECT 1 FROM events WHERE id = 3);

-- Объём, на котором индексы начинают иметь значение: 200 площадок в 20 городах, 5 000 артистов,
-- 100 000 событий на год вперёд, у каждого 1–3 артиста. Значения детерминированы (остатки от деления),
-- поэтому планы и цифры в ADR воспроизводятся.
INSERT INTO venues (name, city)
SELECT 'Venue ' || g, 'City ' || (g % 20)
FROM generate_series(1, 200) g
WHERE (SELECT count(*) FROM venues) < 200;

INSERT INTO performers (name)
SELECT 'Performer ' || g
FROM generate_series(1, 5000) g
WHERE NOT EXISTS (SELECT 1 FROM performers);

-- Массовая загрузка без внешних ключей: с ними PostgreSQL проверяет каждую строку отдельным поиском
-- (200 000 проверок, около 30 секунд), а ADD CONSTRAINT потом проверяет всю таблицу одним проходом
-- (доли секунды). Миграция идёт в транзакции: если вставка упадёт, ключи вернутся откатом.
ALTER TABLE events DROP CONSTRAINT IF EXISTS events_venue_id_fkey;
ALTER TABLE event_performers
    DROP CONSTRAINT IF EXISTS event_performers_event_id_fkey,
    DROP CONSTRAINT IF EXISTS event_performers_performer_id_fkey;

INSERT INTO events (title, venue_id, city, starts_at, sales_open_at, status)
SELECT 'Event ' || g,
       v.id,
       v.city,
       now() + (g % 365) * interval '1 day' + (g % 24) * interval '1 hour',
       now() - interval '1 day',
       -- 80% в продаже, остальное черновики и распроданные
       CASE WHEN g % 10 < 8 THEN 'ON_SALE' WHEN g % 10 = 8 THEN 'DRAFT' ELSE 'SOLD_OUT' END
FROM generate_series(1, 100000) g
JOIN venues v ON v.id = 3 + 1 + (g * 7919) % 200
WHERE (SELECT count(*) FROM events) < 100000;

INSERT INTO event_performers (event_id, performer_id)
SELECT e.id, 1 + (e.id * 31 + k * 977) % 5000
FROM events e, generate_series(0, 2) k
WHERE k <= e.id % 3
  AND NOT EXISTS (SELECT 1 FROM event_performers);

ALTER TABLE events ADD CONSTRAINT events_venue_id_fkey FOREIGN KEY (venue_id) REFERENCES venues (id);
ALTER TABLE event_performers
    ADD CONSTRAINT event_performers_event_id_fkey FOREIGN KEY (event_id) REFERENCES events (id),
    ADD CONSTRAINT event_performers_performer_id_fkey FOREIGN KEY (performer_id) REFERENCES performers (id);

-- после массовой загрузки статистика планировщика устарела, без ANALYZE он может выбрать плохой план
ANALYZE venues;
ANALYZE events;
ANALYZE event_performers;
