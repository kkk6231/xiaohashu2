-- 同一目标原子检查数据和去重记录，返回状态及各字段字符串。
local dataType = redis.call('TYPE', KEYS[1]).ok
local initializedType = redis.call('TYPE', KEYS[2]).ok
local legacySeenType = redis.call('TYPE', KEYS[3]).ok
if dataType ~= 'none' and dataType ~= 'hash' then return redis.error_reply('invalid count type') end
if initializedType ~= 'none' and initializedType ~= 'string' then return redis.error_reply('invalid initialized type') end
if legacySeenType ~= 'none' and legacySeenType ~= 'set' then return redis.error_reply('invalid legacy seen type') end
if dataType == 'none' and (initializedType ~= 'none' or legacySeenType ~= 'none') then return {'missing'} end
local result = {'ok'}
for _, field in ipairs(ARGV) do
    result[#result + 1] = redis.call('HGET', KEYS[1], field) or '0'
end
return result
