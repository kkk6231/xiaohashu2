package com.quanxiaoha.xiaohashu.user.relation.biz.service.impl;

import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.xiaohashu.user.relation.biz.config.RelationProperties;
import com.quanxiaoha.xiaohashu.user.relation.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.user.relation.biz.enums.ResponseCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * @description: 列表缓存互斥锁，协调缓存初始化与增量更新。
 *
 * <p>只做三件事：加锁、拿持有者标识、释放锁，<b>不做租约续期</b>。
 * 持锁区间只是几次 Redis 往返加一次数据库查询，30 秒租约足够；
 * 万一真的超时，写缓存的 Lua 会比对新持有者的 token 并拒绝写入，
 * 因此不会出现"过期线程覆盖新数据"，最多白做一次。</p>
 */
@Component
@Slf4j
public class RelationLocks {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RelationProperties relationProperties;

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end", Long.class);

    /**
     * 生成某类列表的 key 前缀。
     * 前缀里带 hash tag（如 relation:{following:123}:），保证锁、列表、元数据落在同一个 Redis 槽。
     *
     * @param lockType following（关注列表）或 followers（粉丝列表）
     * @param userId   列表归属的用户
     */
    public static String prefix(String lockType, long userId) {
        return RedisKeyConstants.buildRelationKeyPrefix(lockType, userId);
    }

    /** 有界等待用户维度锁；保留中断信号，超时交给接口或 MQ 的失败处理。 */
    public Handle acquire(String lockType, long userId) {
        String lockKey = prefix(lockType, userId) + "lock";
        String lockToken = UUID.randomUUID().toString();
        Duration leaseDuration = Duration.ofMillis(relationProperties.getLockLeaseMillis());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(relationProperties.getLockWaitMillis());
        do {
            if (Thread.currentThread().isInterrupted()) {
                throw new BizException(ResponseCodeEnum.RELATION_BUSY);
            }
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, leaseDuration);
            if (Boolean.TRUE.equals(acquired)) {
                return new Handle(lockKey, lockToken);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                // 小幅随机退避，避免所有等待者同时访问 Redis；不进行 CPU 忙等。
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining,
                        TimeUnit.MILLISECONDS.toNanos(ThreadLocalRandom.current().nextLong(10, 26))));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new BizException(ResponseCodeEnum.RELATION_BUSY);
            }
        } while (System.nanoTime() < deadline);
        throw new BizException(ResponseCodeEnum.RELATION_BUSY);
    }

    /** 单次持锁上下文，配合 try-with-resources 在流程结束时释放。 */
    public final class Handle implements AutoCloseable {
        private final String lockKey;
        private final String lockToken;

        private Handle(String lockKey, String lockToken) {
            this.lockKey = lockKey;
            this.lockToken = lockToken;
        }

        /** 返回本次持有者标识。写缓存时由 Lua 原子检查 Redis 中的实际归属。 */
        public String token() {
            return lockToken;
        }

        /** 释放锁：Lua 只删除属于本次持有者的锁，避免误删已过期后别人拿到的锁。 */
        @Override
        public void close() {
            try {
                stringRedisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(lockKey), lockToken);
            } catch (Exception exception) {
                log.warn("==> 关系锁释放失败，等待租约过期，lockKey: {}", lockKey, exception);
            }
        }
    }
}
