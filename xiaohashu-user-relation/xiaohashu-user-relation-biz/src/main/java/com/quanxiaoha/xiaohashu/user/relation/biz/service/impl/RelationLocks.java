package com.quanxiaoha.xiaohashu.user.relation.biz.service.impl;

import com.quanxiaoha.xiaohashu.user.relation.biz.constant.RedisKeyConstants;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.xiaohashu.user.relation.biz.config.RelationProperties;
import com.quanxiaoha.xiaohashu.user.relation.biz.enums.ResponseCodeEnum;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * @description: 列表缓存互斥锁，协调初始化与增量更新，负责获取、续约和安全释放。
 */
@Component
@Slf4j
public class RelationLocks implements AutoCloseable {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RelationProperties relationProperties;

    private final ScheduledThreadPoolExecutor renewalExecutor = createRenewalExecutor();

    /**
     * 创建锁续约线程池。
     * 开启“取消即移除”，避免大量短操作结束后取消任务堆在延迟队列里。
     */
    private ScheduledThreadPoolExecutor createRenewalExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, this::createRenewalThread);
        // 短操作结束后立即移除续约任务，避免大量取消任务滞留在延迟队列。
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('PEXPIRE',KEYS[1],ARGV[2]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end", Long.class);

    /**
     * 创建续约线程，设为守护线程，避免应用关闭时被阻塞。
     */
    private Thread createRenewalThread(Runnable renewalTask) {
        Thread renewalThread = new Thread(renewalTask, "relation-lock-renewal");
        renewalThread.setDaemon(true);
        return renewalThread;
    }

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
        private final ScheduledFuture<?> renewalTask;
        private volatile boolean leaseLost;

        /**
         * 记录本次持锁的 key 与 token，并按租约的 1/3 周期启动续约任务。
         * 周期小于租约，保证网络抖动时也来得及续上。
         */
        private Handle(String lockKey, String lockToken) {
            this.lockKey = lockKey;
            this.lockToken = lockToken;
            long renewalIntervalMillis = relationProperties.getLockLeaseMillis() / 3;
            renewalTask = renewalExecutor.scheduleAtFixedRate(
                    this::renewLease, renewalIntervalMillis, renewalIntervalMillis, TimeUnit.MILLISECONDS);
        }

        /**
         * 续约一次：Lua 内先比对 token，确认锁还是自己的再延长过期时间。
         * 比对失败或执行异常都标记“租约已丢失”，此后 token() 会直接抛异常，
         * 防止已经失去锁的线程继续往缓存里写数据。
         */
        private void renewLease() {
            try {
                String leaseMillis = String.valueOf(relationProperties.getLockLeaseMillis());
                Long renewed = stringRedisTemplate.execute(RENEW_SCRIPT,
                        Collections.singletonList(lockKey), lockToken, leaseMillis);
                if (!Long.valueOf(1).equals(renewed)) {
                    // 一旦失去租约，本次持锁上下文不再恢复为有效状态
                    leaseLost = true;
                }
            } catch (Exception exception) {
                leaseLost = true;
                log.warn("==> 关系锁续约失败，lockKey: {}", lockKey, exception);
            }
        }

        /** 返回本次持有者标识。发布缓存时仍由 Lua 原子检查 Redis 中的实际归属。 */
        public String token() {
            if (leaseLost) {
                throw new IllegalStateException("关系锁租约已丢失");
            }
            return lockToken;
        }

        /**
         * 返回锁的 key，便于日志排查。
         */
        public String key() {
            return lockKey;
        }

        /**
         * 释放锁：先取消续约任务，再用 Lua 只删除属于本次持有者的锁。
         * 直接 DEL 会误删已过期后由别人拿到的锁，所以必须比对 token。
         */
        @Override
        public void close() {
            renewalTask.cancel(false);
            try {
                // Lua 只删除属于本次持有者的锁，不能误删新持有者的锁
                stringRedisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(lockKey), lockToken);
            } catch (Exception exception) {
                log.warn("==> 关系锁释放失败，等待租约过期，lockKey: {}", lockKey, exception);
            }
        }
    }

    /**
     * 应用关闭时立即停止续约线程池。
     */
    @PreDestroy
    public void close() {
        renewalExecutor.shutdownNow();
    }
}
