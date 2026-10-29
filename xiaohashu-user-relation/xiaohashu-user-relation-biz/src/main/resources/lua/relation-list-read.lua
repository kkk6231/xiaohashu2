-- KEYS：2 列表 ZSet，3 元数据 Hash；ARGV：起始下标、结束下标、可选目标成员
local listKey = KEYS[2]
local metadataKey = KEYS[3]
local cachedCount = redis.call('HGET', metadataKey, 'cached_count')
local expireAt = redis.call('HGET', metadataKey, 'expire_at')
local serverTime = redis.call('TIME')
local nowMillis = tonumber(serverTime[1]) * 1000 + math.floor(tonumber(serverTime[2]) / 1000)

-- 空列表允许 ZSet 不存在；非空列表丢失或成员数不符时，整份缓存失效
if not cachedCount or not expireAt or tonumber(expireAt) <= nowMillis
        or redis.call('ZCARD', listKey) ~= tonumber(cachedCount) then
    redis.call('DEL', listKey, metadataKey)
    return {}
end

local result = {redis.call('HGET', metadataKey, 'complete'), cachedCount, expireAt}
local members = redis.call('ZREVRANGE', listKey, ARGV[1], ARGV[2], 'WITHSCORES')
for _, value in ipairs(members) do
    result[#result + 1] = value
end
-- 粉丝增量同步只读尾部两条，再附带目标是否存在及旧分数；不修改列表。
-- 目标可能已在尾部中，Java 使用 ID 去重。
if ARGV[3] and ARGV[3] ~= '' then
    local targetScore = redis.call('ZSCORE', listKey, ARGV[3])
    if targetScore then
        result[#result + 1] = ARGV[3]
        result[#result + 1] = targetScore
    end
end
return result
