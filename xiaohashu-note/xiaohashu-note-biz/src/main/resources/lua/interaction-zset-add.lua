-- 原子写入最近互动列表，超过容量时只移除最早的记录。
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
local size = redis.call('ZCARD', KEYS[1])
local limit = tonumber(ARGV[3])
if size > limit then
    redis.call('ZREMRANGEBYRANK', KEYS[1], 0, size - limit - 1)
end
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
redis.call('SET', KEYS[2], ARGV[5], 'EX', tonumber(ARGV[4]))
redis.call('SET', KEYS[3], ARGV[5] .. ':1', 'EX', tonumber(ARGV[6]))
return 1
