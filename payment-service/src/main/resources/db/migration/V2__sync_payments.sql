-- Блок 9: оплата синхронная (REST), а не через Kafka. Платёж можно вернуть или отменить до проведения.
-- Отмена неизвестного платежа пишет строку VOIDED без суммы: запоздавший authorize с тем же id её увидит
-- и не спишет деньги. Поэтому сумма, бронь и пользователь стали необязательными.
ALTER TABLE payments DROP COLUMN result_message_id;
ALTER TABLE payments ALTER COLUMN booking_id DROP NOT NULL;
ALTER TABLE payments ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE payments ALTER COLUMN amount DROP NOT NULL;
