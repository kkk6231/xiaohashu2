-- 原子删除互动记录并登记最近一次缓存修改。
redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('SET', KEYS[2], ARGV[2], 'EX', tonumber(ARGV[3]))
redis.call('SET', KEYS[3], ARGV[2] .. ':0', 'EX', tonumber(ARGV[4]))
return 1
