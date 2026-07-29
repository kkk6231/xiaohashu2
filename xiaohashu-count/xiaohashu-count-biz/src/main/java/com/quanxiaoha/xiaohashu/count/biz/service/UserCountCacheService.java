package com.quanxiaoha.xiaohashu.count.biz.service;

import com.quanxiaoha.xiaohashu.count.dto.resp.FindUserCountByIdRspDTO;

/**
 * 用户计数 Redis 缓存服务。
 *
 * <p>该服务统一处理用户维度计数 Hash 的初始化和增量更新，避免查询链路、
 * MQ 写链路各自实现缓存重建逻辑后出现覆盖问题。</p>
 */
public interface UserCountCacheService {

    /**
     * 查询用户计数；Redis 未命中时按 DB 完整快照初始化。
     *
     * @param userId 用户 ID
     * @return 用户完整计数
     */
    FindUserCountByIdRspDTO getOrInitUserCount(Long userId);

    /**
     * 初始化或增量更新用户计数。
     *
     * <p>Redis 存在时只对当前字段执行 HINCRBY；Redis 不存在时，从 DB 读取完整快照，
     * 把当前字段叠加本次 delta 后一次性初始化完整 Hash。</p>
     *
     * @param userId 用户 ID
     * @param field 需要增量更新的 Hash 字段
     * @param delta 本次增量，允许为负数
     */
    void initOrIncrementUserCount(Long userId, String field, Long delta);

    /**
     * 带 Redis 幂等判断的初始化或增量更新。
     *
     * <p>用于非聚合计数消息，例如关注数。只有同一个 eventId 首次消费时才会修改 Redis，
     * 重复消费会直接返回 0，避免 Redis 计数重复加减。</p>
     *
     * @param userId 用户 ID
     * @param idempotentKey 幂等 Key，例如 idem:following:{eventId}
     * @param idempotentTtlSeconds 幂等 Key 过期时间，单位秒
     * @param field 需要增量更新的 Hash 字段
     * @param delta 本次增量，允许为负数
     * @return 1 表示首次处理，0 表示重复处理
     */
    Long idempotentInitOrIncrementUserCount(Long userId, String idempotentKey, Long idempotentTtlSeconds, String field, Long delta);
}
