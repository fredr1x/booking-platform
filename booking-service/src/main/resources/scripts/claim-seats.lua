-- Claim всех мест брони одной атомарной операцией: либо поставлены все ключи, либо ни одного.
-- KEYS: ключи мест. ARGV[1]: владелец, ARGV[2]: TTL в мс.
-- Ответ: {'OK', ключи, поставленные этим вызовом...} или {'BUSY', номер занятого ключа в KEYS}.
local claimed = {'OK'}
for i, key in ipairs(KEYS) do
    local holder = redis.call('GET', key)
    if not holder then
        claimed[#claimed + 1] = key
    elseif holder ~= ARGV[1] then
        return {'BUSY', tostring(i)}
    end
end
for i = 2, #claimed do
    redis.call('SET', claimed[i], ARGV[1], 'PX', ARGV[2])
end
return claimed
