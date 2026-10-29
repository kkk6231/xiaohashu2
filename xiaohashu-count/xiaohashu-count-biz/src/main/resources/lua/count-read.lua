-- 与首次写入互斥执行，避免 GET 和 EXISTS 观察到不同时间的状态。
local data = redis.call('GET', KEYS[1])
if not data and redis.call('EXISTS', KEYS[2]) == 1 then
    return redis.error_reply('counter data missing')
end
-- false 表示目标尚未初始化；已有 String 原样返回，不转换二进制内容。
return data
