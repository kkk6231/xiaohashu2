-- KEYS[1]：二进制计数 String；KEYS[2]：该目标已处理的事件 ID 集合。
-- ARGV[1]：字段数；ARGV[2]：[{id: 事件键, values: 按字段顺序排列的增量}]。
-- 字段顺序由 Java 的 USER_FIELDS / NOTE_FIELDS 定义，每项使用有符号 64 位存储。
-- 每个目标独立去重；写入前完成所有字段计算，避免参数错误造成部分写入。
local count = tonumber(ARGV[1])
local events = cjson.decode(ARGV[2])
local raw = redis.call('GET', KEYS[1])
local seenType = redis.call('TYPE', KEYS[2]).ok
if seenType ~= 'none' and seenType ~= 'set' then return redis.error_reply('invalid seen type') end
if not raw and seenType ~= 'none' then return redis.error_reply('counter data missing') end
if raw and string.len(raw) ~= count * 8 then return redis.error_reply('invalid counter length') end
local limit = 9007199254740991
-- 1. 读取所有字段。Redis 位偏移以 bit 为单位，因此每个字段间隔 64。
local values = {}
for i = 1, count do
    values[i] = redis.call('BITFIELD', KEYS[1], 'GET', 'i64', (i - 1) * 64)[1]
end
-- 2. 跳过历史重复和批内重复事件，只聚合尚未处理的增量。
local ids = {}
local batchSeen = {}
for _, event in ipairs(events) do
    if not batchSeen[event.id] and redis.call('SISMEMBER', KEYS[2], event.id) == 0 then
        batchSeen[event.id] = true
        table.insert(ids, event.id)
        for i = 1, count do
            local delta = event.values[i]
            if not delta or delta ~= math.floor(delta) or
                (delta > 0 and values[i] > limit - delta) or
                (delta < 0 and values[i] < -limit - delta) then
                return redis.error_reply('counter overflow')
            end
            values[i] = values[i] + delta
        end
    end
end
if #ids == 0 then return 0 end
-- 3. 所有计算完成后一次写回计数，再记录事件 ID；整个脚本不被其他命令穿插。
-- 内部允许负值，保证删除事件先到、互动增量后到时仍可相互抵消。
local command = {'BITFIELD', KEYS[1]}
for i = 1, count do
    table.insert(command, 'SET')
    table.insert(command, 'i64')
    table.insert(command, (i - 1) * 64)
    table.insert(command, string.format('%.0f', values[i]))
end
redis.call(unpack(command))
redis.call('SADD', KEYS[2], unpack(ids))
return #ids
