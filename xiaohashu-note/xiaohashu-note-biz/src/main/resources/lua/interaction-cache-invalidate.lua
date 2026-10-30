-- 仅清除本次事件的短期状态，避免覆盖同一笔记的新操作。
local pending = redis.call('GET', KEYS[3])
if pending == ARGV[1] .. ':1' or pending == ARGV[1] .. ':0' then
    if ARGV[2] ~= '' then
        redis.call('SET', KEYS[3], ARGV[2], 'EX', tonumber(ARGV[3]))
    else
        redis.call('DEL', KEYS[3])
    end
end
-- 仅当整个列表没有后续修改时才失效列表。
if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end
redis.call('DEL', KEYS[1], KEYS[2])
return 1
