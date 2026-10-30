-- KEYS：1 锁，2 列表 ZSet，3 元数据 Hash
-- ARGV：1 锁标识，2 操作类型，3 初始化 TTL（毫秒），4 容量上限，5 删除数量，
--       之后为删除的 member，再之后为添加的 score/member 对
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
    expireAt = nowMillis + tonumber(ARGV[3])
else
    expireAt = tonumber(redis.call('HGET', metadataKey, 'expire_at'))
    -- 缓存未初始化或已过期：不在这里重建，交给读路径；返回 0 表示本次没有写入
    if not expireAt or expireAt <= nowMillis then
        redis.call('DEL', listKey, metadataKey)
        return 0
    end
end

local removeCount = tonumber(ARGV[5])
for index = 1, removeCount do
    redis.call('ZREM', listKey, ARGV[5 + index])
end
for index = 6 + removeCount, #ARGV, 2 do
    redis.call('ZADD', listKey, ARGV[index], ARGV[index + 1])
end

-- 超过容量时删掉最旧的成员（score 最小的），保持"最近 N 条"的语义
local cachedCount = redis.call('ZCARD', listKey)
local cacheLimit = tonumber(ARGV[4])
if cachedCount > cacheLimit then
    redis.call('ZREMRANGEBYRANK', listKey, 0, cachedCount - cacheLimit - 1)
end

redis.call('HSET', metadataKey, 'expire_at', expireAt)
-- 增量更新保留原到期时间，不能每次续期导致缓存永不过期
redis.call('PEXPIREAT', listKey, expireAt)
redis.call('PEXPIREAT', metadataKey, expireAt)
return 1
