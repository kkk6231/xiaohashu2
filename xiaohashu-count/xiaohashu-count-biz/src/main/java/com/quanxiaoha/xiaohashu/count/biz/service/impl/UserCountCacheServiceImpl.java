package com.quanxiaoha.xiaohashu.count.biz.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.quanxiaoha.xiaohashu.count.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.dataobject.UserCountDO;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.UserCountDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.service.UserCountCacheService;
import com.quanxiaoha.xiaohashu.count.dto.resp.FindUserCountByIdRspDTO;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 用户计数 Redis 缓存服务实现。
 *
 * <p>这个类是用户计数闭环的核心：查询线程和 MQ 写线程都通过它初始化 Redis，
 * 避免多处手写初始化逻辑导致字段缺失、旧 DB 快照覆盖 Redis 增量等问题。</p>
 */
@Service
public class UserCountCacheServiceImpl implements UserCountCacheService {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;
    @Resource
    private UserCountDOMapper userCountDOMapper;

    /**
     * 用户计数 Hash 过期时间。
     */
    @Value("${xiaohashu.count.cache.ttl-seconds:86400}")
    private Long countCacheTtlSeconds;

    /**
     * 查询链路使用：仅在 Redis Key 不存在时，按 DB 完整快照初始化 Hash。
     */
    private static final DefaultRedisScript<Long> INIT_IF_ABSENT_SCRIPT;

    /**
     * 写链路使用：Redis 存在则增量当前字段；Redis 不存在则初始化完整 Hash。
     */
    private static final DefaultRedisScript<Long> INIT_OR_INCREMENT_SCRIPT;

    /**
     * 非聚合写链路使用：先判断 eventId 是否重复，再执行初始化或增量。
     */
    private static final DefaultRedisScript<Long> IDEMPOTENT_INIT_OR_INCREMENT_SCRIPT;

    static {
        INIT_IF_ABSENT_SCRIPT = new DefaultRedisScript<>();
        INIT_IF_ABSENT_SCRIPT.setResultType(Long.class);
        INIT_IF_ABSENT_SCRIPT.setScriptText("""
                if redis.call('EXISTS', KEYS[1]) == 1 then
                    return 0
                end
                redis.call('HSET', KEYS[1],
                    ARGV[1], ARGV[2],
                    ARGV[3], ARGV[4],
                    ARGV[5], ARGV[6],
                    ARGV[7], ARGV[8],
                    ARGV[9], ARGV[10])
                redis.call('EXPIRE', KEYS[1], ARGV[11])
                return 1
                """);

        INIT_OR_INCREMENT_SCRIPT = new DefaultRedisScript<>();
        INIT_OR_INCREMENT_SCRIPT.setResultType(Long.class);
        INIT_OR_INCREMENT_SCRIPT.setScriptText("""
                if redis.call('EXISTS', KEYS[1]) == 1 then
                    redis.call('HINCRBY', KEYS[1], ARGV[11], ARGV[12])
                    return 1
                end
                redis.call('HSET', KEYS[1],
                    ARGV[1], ARGV[2],
                    ARGV[3], ARGV[4],
                    ARGV[5], ARGV[6],
                    ARGV[7], ARGV[8],
                    ARGV[9], ARGV[10])
                redis.call('EXPIRE', KEYS[1], ARGV[13])
                return 1
                """);

        IDEMPOTENT_INIT_OR_INCREMENT_SCRIPT = new DefaultRedisScript<>();
        IDEMPOTENT_INIT_OR_INCREMENT_SCRIPT.setResultType(Long.class);
        IDEMPOTENT_INIT_OR_INCREMENT_SCRIPT.setScriptText("""
                if redis.call('SETNX', KEYS[1], '1') == 0 then
                    return 0
                end
                redis.call('EXPIRE', KEYS[1], ARGV[14])
                if redis.call('EXISTS', KEYS[2]) == 1 then
                    redis.call('HINCRBY', KEYS[2], ARGV[11], ARGV[12])
                    return 1
                end
                redis.call('HSET', KEYS[2],
                    ARGV[1], ARGV[2],
                    ARGV[3], ARGV[4],
                    ARGV[5], ARGV[6],
                    ARGV[7], ARGV[8],
                    ARGV[9], ARGV[10])
                redis.call('EXPIRE', KEYS[2], ARGV[13])
                return 1
                """);
    }

    @Override
    public FindUserCountByIdRspDTO getOrInitUserCount(Long userId) {
        String redisKey = RedisKeyConstants.buildCountUserKey(userId);
        Map<Object, Object> countHash = redisTemplate.opsForHash().entries(redisKey);

        if (CollUtil.isEmpty(countHash)) {
            FindUserCountByIdRspDTO dbSnapshot = loadUserCountSnapshot(userId);

            // 查询链路只初始化 DB 快照，不叠加 delta；Lua 内部会判断 Key 是否已被并发写线程创建。
            redisTemplate.execute(INIT_IF_ABSENT_SCRIPT,
                    Collections.singletonList(redisKey),
                    buildInitArgs(dbSnapshot, countCacheTtlSeconds));

            // 初始化后回读 Redis：如果并发写线程先创建了 Key，这里能读到写线程处理后的最新值。
            countHash = redisTemplate.opsForHash().entries(redisKey);
            if (CollUtil.isEmpty(countHash)) {
                return dbSnapshot;
            }
        }

        return convertFromRedis(userId, countHash);
    }

    @Override
    public void initOrIncrementUserCount(Long userId, String field, Long delta) {
        String redisKey = RedisKeyConstants.buildCountUserKey(userId);

        // Redis 不存在时要用“完整 DB 快照 + 本次 delta”初始化，不能只初始化当前字段。
        FindUserCountByIdRspDTO snapshot = applyDelta(loadUserCountSnapshot(userId), field, delta);

        redisTemplate.execute(INIT_OR_INCREMENT_SCRIPT,
                Collections.singletonList(redisKey),
                buildInitOrIncrementArgs(snapshot, field, delta, countCacheTtlSeconds));
    }

    @Override
    public Long idempotentInitOrIncrementUserCount(Long userId, String idempotentKey, Long idempotentTtlSeconds, String field, Long delta) {
        String redisKey = RedisKeyConstants.buildCountUserKey(userId);

        // 非聚合消息按 eventId 做 Redis 幂等；首次消息才会写 Redis，重复消息返回 0。
        FindUserCountByIdRspDTO snapshot = applyDelta(loadUserCountSnapshot(userId), field, delta);

        return redisTemplate.execute(IDEMPOTENT_INIT_OR_INCREMENT_SCRIPT,
                Arrays.asList(idempotentKey, redisKey),
                buildIdempotentInitOrIncrementArgs(snapshot, field, delta, countCacheTtlSeconds, idempotentTtlSeconds));
    }

    /**
     * 从 t_user_count 加载完整用户计数快照。
     *
     * <p>如果 DB 没有该用户的计数行，说明所有计数字段都可以从 0 开始兜底。</p>
     */
    private FindUserCountByIdRspDTO loadUserCountSnapshot(Long userId) {
        UserCountDO userCountDO = userCountDOMapper.selectByUserId(userId);
        if (Objects.isNull(userCountDO)) {
            return buildZeroCount(userId);
        }

        return FindUserCountByIdRspDTO.builder()
                .userId(userId)
                .fansTotal(nullToZero(userCountDO.getFansTotal()))
                .followingTotal(nullToZero(userCountDO.getFollowingTotal()))
                .noteTotal(nullToZero(userCountDO.getNoteTotal()))
                .likeTotal(nullToZero(userCountDO.getLikeTotal()))
                .collectTotal(nullToZero(userCountDO.getCollectTotal()))
                .build();
    }

    /**
     * 把本次 MQ 增量叠加到 DB 快照中的目标字段。
     *
     * <p>其他字段必须保留 DB 快照值，不能置 0，否则缓存重建后查询其他字段会得到错误结果。</p>
     */
    private FindUserCountByIdRspDTO applyDelta(FindUserCountByIdRspDTO snapshot, String field, Long delta) {
        Long safeDelta = nullToZero(delta);
        if (Objects.equals(field, RedisKeyConstants.FIELD_FANS_TOTAL)) {
            snapshot.setFansTotal(nullToZero(snapshot.getFansTotal()) + safeDelta);
        } else if (Objects.equals(field, RedisKeyConstants.FIELD_FOLLOWING_TOTAL)) {
            snapshot.setFollowingTotal(nullToZero(snapshot.getFollowingTotal()) + safeDelta);
        } else if (Objects.equals(field, RedisKeyConstants.FIELD_NOTE_TOTAL)) {
            snapshot.setNoteTotal(nullToZero(snapshot.getNoteTotal()) + safeDelta);
        } else if (Objects.equals(field, RedisKeyConstants.FIELD_LIKE_TOTAL)) {
            snapshot.setLikeTotal(nullToZero(snapshot.getLikeTotal()) + safeDelta);
        } else if (Objects.equals(field, RedisKeyConstants.FIELD_COLLECT_TOTAL)) {
            snapshot.setCollectTotal(nullToZero(snapshot.getCollectTotal()) + safeDelta);
        } else {
            throw new IllegalArgumentException("未知用户计数字段: " + field);
        }
        return snapshot;
    }

    /**
     * 构造完整 Hash 初始化参数。
     *
     * <p>ARGV[1] ~ ARGV[10] 是 field/value 成对参数，ARGV[11] 是缓存 TTL。</p>
     */
    private Object[] buildInitArgs(FindUserCountByIdRspDTO snapshot, Long ttlSeconds) {
        return new Object[]{
                RedisKeyConstants.FIELD_FANS_TOTAL, String.valueOf(nullToZero(snapshot.getFansTotal())),
                RedisKeyConstants.FIELD_FOLLOWING_TOTAL, String.valueOf(nullToZero(snapshot.getFollowingTotal())),
                RedisKeyConstants.FIELD_NOTE_TOTAL, String.valueOf(nullToZero(snapshot.getNoteTotal())),
                RedisKeyConstants.FIELD_LIKE_TOTAL, String.valueOf(nullToZero(snapshot.getLikeTotal())),
                RedisKeyConstants.FIELD_COLLECT_TOTAL, String.valueOf(nullToZero(snapshot.getCollectTotal())),
                String.valueOf(ttlSeconds)
        };
    }

    /**
     * 构造写链路 Lua 参数。
     *
     * <p>前 10 个参数用于 Redis 不存在时初始化完整 Hash；ARGV[11]/ARGV[12]
     * 用于 Redis 已存在时增量当前字段；ARGV[13] 是缓存 TTL。</p>
     */
    private Object[] buildInitOrIncrementArgs(FindUserCountByIdRspDTO snapshot, String field, Long delta, Long ttlSeconds) {
        Object[] initArgs = buildInitArgs(snapshot, ttlSeconds);
        return new Object[]{
                initArgs[0], initArgs[1],
                initArgs[2], initArgs[3],
                initArgs[4], initArgs[5],
                initArgs[6], initArgs[7],
                initArgs[8], initArgs[9],
                field, String.valueOf(delta),
                initArgs[10]
        };
    }

    /**
     * 构造带幂等判断的写链路 Lua 参数。
     *
     * <p>ARGV[14] 是 eventId 幂等 Key 的 TTL，其余参数与普通写链路一致。</p>
     */
    private Object[] buildIdempotentInitOrIncrementArgs(FindUserCountByIdRspDTO snapshot, String field, Long delta,
                                                        Long cacheTtlSeconds, Long idempotentTtlSeconds) {
        Object[] initOrIncrementArgs = buildInitOrIncrementArgs(snapshot, field, delta, cacheTtlSeconds);
        return new Object[]{
                initOrIncrementArgs[0], initOrIncrementArgs[1],
                initOrIncrementArgs[2], initOrIncrementArgs[3],
                initOrIncrementArgs[4], initOrIncrementArgs[5],
                initOrIncrementArgs[6], initOrIncrementArgs[7],
                initOrIncrementArgs[8], initOrIncrementArgs[9],
                initOrIncrementArgs[10], initOrIncrementArgs[11],
                initOrIncrementArgs[12],
                String.valueOf(idempotentTtlSeconds)
        };
    }

    /**
     * 将 Redis Hash 转换为响应 DTO。
     */
    private FindUserCountByIdRspDTO convertFromRedis(Long userId, Map<Object, Object> countHash) {
        return FindUserCountByIdRspDTO.builder()
                .userId(userId)
                .fansTotal(getLongValue(countHash, RedisKeyConstants.FIELD_FANS_TOTAL))
                .followingTotal(getLongValue(countHash, RedisKeyConstants.FIELD_FOLLOWING_TOTAL))
                .noteTotal(getLongValue(countHash, RedisKeyConstants.FIELD_NOTE_TOTAL))
                .likeTotal(getLongValue(countHash, RedisKeyConstants.FIELD_LIKE_TOTAL))
                .collectTotal(getLongValue(countHash, RedisKeyConstants.FIELD_COLLECT_TOTAL))
                .build();
    }

    /**
     * 构造 DB 无计数行时的全 0 兜底快照。
     */
    private FindUserCountByIdRspDTO buildZeroCount(Long userId) {
        return FindUserCountByIdRspDTO.builder()
                .userId(userId)
                .fansTotal(0L)
                .followingTotal(0L)
                .noteTotal(0L)
                .likeTotal(0L)
                .collectTotal(0L)
                .build();
    }

    private Long getLongValue(Map<Object, Object> countHash, String field) {
        Object value = countHash.get(field);
        if (Objects.isNull(value)) {
            return 0L;
        }
        return Long.valueOf(value.toString());
    }

    private Long nullToZero(Long value) {
        return Objects.isNull(value) ? 0L : value;
    }
}
