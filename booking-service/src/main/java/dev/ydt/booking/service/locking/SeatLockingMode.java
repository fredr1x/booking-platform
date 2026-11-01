package dev.ydt.booking.service.locking;

/** Способы защитить место от двойной продажи. Сравнение и выбор в docs/adr/0001-seat-locking.md. */
public enum SeatLockingMode {
    /** Без защиты: проверка и запись разделены. Только для демонстрации гонки. */
    NAIVE,
    /** ReentrantLock в памяти JVM. Работает на одном инстансе, ломается на двух. */
    JVM_LOCK,
    /** @Version: UPDATE ... WHERE version = ?, проигравший получает конфликт. */
    OPTIMISTIC,
    /** SELECT ... FOR UPDATE: конкуренты ждут, пока держатель блокировки закоммитит. */
    PESSIMISTIC,
    /** SELECT ... FOR UPDATE SKIP LOCKED: конкуренты не ждут, а сразу считают место занятым. */
    SKIP_LOCKED,
    /** UPDATE ... WHERE status = 'AVAILABLE': проверка и запись одним оператором. */
    ATOMIC,
    /**
     * Сначала claim места в Redis (SET NX), потом тот же атомарный UPDATE. Проигравшие отсеиваются в Redis
     * и не занимают соединение с БД. Режим по умолчанию.
     */
    REDIS
}
