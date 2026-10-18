-- Страховка от двойной продажи на уровне БД: одно место может быть только в одной активной брони.
-- Код блокировок может ошибиться, ограничение в БД нет.
ALTER TABLE booking_seats ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE booking_seats bs
SET active = FALSE
FROM bookings b
WHERE b.id = bs.booking_id
  AND b.status IN ('CANCELLED', 'EXPIRED');

CREATE UNIQUE INDEX uq_booking_seats_active_seat ON booking_seats (seat_id) WHERE active;
