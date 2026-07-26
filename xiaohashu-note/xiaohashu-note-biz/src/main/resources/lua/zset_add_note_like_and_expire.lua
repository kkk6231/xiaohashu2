local key = KEYS[1]
local noteId = ARGV[1]
local timeStamp = ARGV[2]
local expireSeconds = ARGV[3]

redis.call("ZADD", key, timeStamp, noteId)

redis.call("EXPIRE", key, expireSeconds)

return 0