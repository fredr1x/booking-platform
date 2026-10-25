-- Индексы под реальные запросы каталога, разбор планов в docs/adr/0002-catalog-queries.md.
-- Частичные: в выдачу попадают только события в продаже, черновики и прошедшие индекс не раздувают.

-- лента «что идёт в городе»: WHERE city = ? AND (starts_at, id) > (?, ?) ORDER BY starts_at, id LIMIT n
CREATE INDEX idx_events_on_sale_city ON events (city, starts_at, id) WHERE status = 'ON_SALE';

-- та же лента без фильтра по городу
CREATE INDEX idx_events_on_sale ON events (starts_at, id) WHERE status = 'ON_SALE';
