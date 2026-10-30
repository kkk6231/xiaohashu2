-- KEYS：2 列表 ZSet，3 元数据 Hash；ARGV：起始下标、结束下标
local listKey = KEYS[2]
local metadataKey = KEYS[3]
local expireAt = redis.call('HGET', metadataKey, 'expire_at')
local serverTime = redis.call('TIME')
local nowMillis = tonumber(serverTime[1]) * 1000 + math.floor(tonumber(serverTime[2]) / 1000)

-- 缓存未初始化或已过期：整份作废，交给读路径重建
if not expireAt or tonumber(expireAt) <= nowMillis then
    redis.call('DEL', listKey, metadataKey)
    return {}
end

-- 第一个返回值是整份 ZSet 的成员数（不是本页条数），供关注列表当总数使用
local result = {redis.call('ZCARD', listKey)}
local members = redis.call('ZREVRANGE', listKey, ARGV[1], ARGV[2], 'WITHSCORES')
for _, value in ipairs(members) do
    result[#result + 1] = value
end
return result
