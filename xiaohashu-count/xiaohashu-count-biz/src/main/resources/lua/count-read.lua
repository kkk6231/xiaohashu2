-- 同一目标原子检查数据和去重记录，返回状态及各字段字符串。
local dataType = redis.call('TYPE', KEYS[1]).ok
local seenType = redis.call('TYPE', KEYS[2]).ok
if dataType ~= 'none' and dataType ~= 'hash' then return redis.error_reply('invalid count type') end
if seenType ~= 'none' and seenType ~= 'set' then return redis.error_reply('invalid seen type') end
if dataType == 'none' and seenType ~= 'none' then return {'missing'} end
local result = {'ok'}
for _, field in ipairs(ARGV) do
    result[#result + 1] = redis.call('HGET', KEYS[1], field) or '0'
end
return result
