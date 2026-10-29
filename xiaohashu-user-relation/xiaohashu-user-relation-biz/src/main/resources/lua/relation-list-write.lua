-- KEYS：1 锁，2 列表 ZSet，3 元数据 Hash
-- ARGV：1 锁标识，2 操作类型，3 完整标记，4 原到期时间，5 初始化 TTL（毫秒），
--       6 容量上限，7 删除数量，之后为删除的 member，再之后为添加的 score/member 对
local lockKey = KEYS[1]
local listKey = KEYS[2]
local metadataKey = KEYS[3]

-- 校验持有者，避免过期任务覆盖新任务的缓存
if redis.call('GET', lockKey) ~= ARGV[1] then
    return -1
end

local serverTime = redis.call('TIME')
local nowMillis = tonumber(serverTime[1]) * 1000 + math.floor(tonumber(serverTime[2]) / 1000)
local expireAt
if ARGV[2] == 'init' then
    redis.call('DEL', listKey, metadataKey)
    expireAt = nowMillis + tonumber(ARGV[5])
else
    local cachedCount = redis.call('HGET', metadataKey, 'cached_count')
    expireAt = tonumber(redis.call('HGET', metadataKey, 'expire_at'))
    if not cachedCount or not expireAt or expireAt <= nowMillis
            or redis.call('ZCARD', listKey) ~= tonumber(cachedCount) then
        redis.call('DEL', listKey, metadataKey)
        return 0
    end
    -- 缓存已重新初始化时，不能发布基于旧快照计算的增量
    if expireAt ~= tonumber(ARGV[4]) then
        return 0
    end
end

local removeCount = tonumber(ARGV[7])
for index = 1, removeCount do
    redis.call('ZREM', listKey, ARGV[7 + index])
end
for index = 8 + removeCount, #ARGV, 2 do
    redis.call('ZADD', listKey, ARGV[index], ARGV[index + 1])
end

-- 超过容量时删除最早的成员，并标记为截断列表
local cachedCount = redis.call('ZCARD', listKey)
local cacheLimit = tonumber(ARGV[6])
local complete = ARGV[3]
if cachedCount > cacheLimit then
    redis.call('ZREMRANGEBYRANK', listKey, 0, cachedCount - cacheLimit - 1)
    cachedCount = cacheLimit
    complete = '0'
end

redis.call('HSET', metadataKey, 'cached_count', cachedCount, 'complete', complete, 'expire_at', expireAt)
-- 增量更新保留原到期时间，不能每次续期导致缓存永不过期
redis.call('PEXPIREAT', listKey, expireAt)
redis.call('PEXPIREAT', metadataKey, expireAt)
return 1
