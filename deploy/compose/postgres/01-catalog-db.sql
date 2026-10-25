-- Первый запуск контейнера: отдельная база для catalog-service (database per service в одном инстансе Postgres).
-- На уже созданном томе скрипт не выполняется, тогда базу создают вручную: CREATE DATABASE catalog;
CREATE DATABASE catalog OWNER booking;
