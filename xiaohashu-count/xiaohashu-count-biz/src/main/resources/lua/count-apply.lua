-- 先完成类型、字段和数值检查，再写入计数和事件记录。
local dataType = redis.call('TYPE', KEYS[1]).ok
local initializedType = redis.call('TYPE', KEYS[2]).ok
local seenType = redis.call('TYPE', KEYS[3]).ok
local legacySeenType = redis.call('TYPE', KEYS[4]).ok
if dataType ~= 'none' and dataType ~= 'hash' then return redis.error_reply('invalid count type') end
if initializedType ~= 'none' and initializedType ~= 'string' then return redis.error_reply('invalid initialized type') end
if seenType ~= 'none' and seenType ~= 'set' then return redis.error_reply('invalid seen type') end
if legacySeenType ~= 'none' and legacySeenType ~= 'set' then return redis.error_reply('invalid legacy seen type') end
if dataType == 'none' and (initializedType ~= 'none' or seenType ~= 'none' or legacySeenType ~= 'none') then
    return redis.error_reply('count data missing')
end
local events = cjson.decode(ARGV[1])
local retentionSeconds = tonumber(ARGV[2])
if not retentionSeconds or retentionSeconds <= 0 or retentionSeconds ~= math.floor(retentionSeconds) then
    return redis.error_reply('invalid seen retention')
end
local ids, batchSeen, values = {}, {}, {}
local limit = 9007199254740991
for _, event in ipairs(events) do
    if not batchSeen[event.id]
            and redis.call('SISMEMBER', KEYS[3], event.id) == 0
            and redis.call('SISMEMBER', KEYS[4], event.id) == 0 then
        batchSeen[event.id] = true
        table.insert(ids, event.id)
        for field, delta in pairs(event.deltas) do
            if values[field] == nil then
                local raw = redis.call('HGET', KEYS[1], field)
                if raw and not string.match(raw, '^%-?%d+$') then return redis.error_reply('invalid counter value') end
                values[field] = tonumber(raw or '0')
                if not values[field] or math.abs(values[field]) > limit then return redis.error_reply('invalid counter value') end
            end
            if type(delta) ~= 'number' or delta ~= math.floor(delta) or math.abs(delta) > limit or
                (delta > 0 and values[field] > limit - delta) or
                (delta < 0 and values[field] < -limit - delta) then
                return redis.error_reply('invalid counter delta')
            end
            values[field] = values[field] + delta
        end
    end
end
if #ids == 0 then return 0 end
for field, value in pairs(values) do
    redis.call('HSET', KEYS[1], field, string.format('%.0f', value))
end
redis.call('SET', KEYS[2], '1')
redis.call('SADD', KEYS[3], unpack(ids))
redis.call('EXPIRE', KEYS[3], retentionSeconds)
return #ids
