-- Один проход очереди: забрать первых N и отметить их пропущенными, одной атомарной операцией.
-- KEYS[1]: очередь, KEYS[2]: отметка прохода. ARGV: N, TTL пропуска в мс, TTL отметки в мс, префикс ключа пропуска.
-- Отметка прохода: при нескольких инстансах за интервал проход делает один, скорость пропуска не умножается.
if not redis.call('SET', KEYS[2], '1', 'NX', 'PX', ARGV[3]) then
    return 0
end
local popped = redis.call('ZPOPMIN', KEYS[1], ARGV[1])
for i = 1, #popped, 2 do
    redis.call('SET', ARGV[4] .. popped[i], '1', 'PX', ARGV[2])
end
return #popped / 2
