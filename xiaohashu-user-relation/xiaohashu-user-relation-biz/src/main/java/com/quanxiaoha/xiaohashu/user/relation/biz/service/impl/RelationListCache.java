package com.quanxiaoha.xiaohashu.user.relation.biz.service.impl;

import com.quanxiaoha.xiaohashu.user.relation.biz.config.RelationProperties;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject.RelationDO;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.mapper.RelationDOMapper;
import com.quanxiaoha.xiaohashu.user.relation.biz.enums.FollowUnfollowTypeEnum;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.dto.RelationCacheEntryDTO;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.dto.RelationCacheSnapshotDTO;
import jakarta.annotation.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * @description: 关注和粉丝列表缓存。读路径按需从主库加载，写路径按事件增量维护；
 * 粉丝列表只保留最近 N 位，超出范围的页由调用方直接拒绝，不在这里回源。
 */
@Service
public class RelationListCache {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RelationDOMapper relationDOMapper;
    @Resource
    private RelationLocks relationLocks;
    @Resource
    private RelationProperties relationProperties;
    @Resource
    private RelationDatabaseRateLimiter relationDatabaseRateLimiter;

    private static final String FOLLOWING_CACHE = "following";
    private static final String FANS_CACHE = "followers";
    private static final DefaultRedisScript<List> READ_LIST_SCRIPT = loadScript("relation-list-read.lua", List.class);
    private static final DefaultRedisScript<Long> WRITE_LIST_SCRIPT = loadScript("relation-list-write.lua", Long.class);

    /**
     * 从 classpath 加载 Lua 脚本并指定返回类型。
     * 脚本在类加载时只读一次，之后复用同一个 DefaultRedisScript 实例。
     */
    private static <T> DefaultRedisScript<T> loadScript(String scriptName, Class<T> resultType) {
        DefaultRedisScript<T> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("lua/" + scriptName));
        redisScript.setResultType(resultType);
        return redisScript;
    }

    /** 将 ID 补齐为 19 位，使 ZSet 同分排序与数据库的数字 ID 排序一致。 */
    public static String member(long userId) {
        if (userId < 0) {
            throw new IllegalArgumentException("用户 ID 不能小于零");
        }
        return String.format(Locale.ROOT, "%019d", userId);
    }

    /** 数据库时间不带时区，统一按 UTC 编码为毫秒，不能使用消息消费时间。 */
    public static long score(LocalDateTime updateTime) {
        return updateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /**
     * 生成锁、列表、元数据三个 key。
     * 顺序必须与 Lua 的 KEYS[1..3] 一致；三者共用 hash tag，保证同一槽位、能被一个脚本原子操作。
     */
    private List<String> buildCacheKeys(String cacheType, long userId) {
        String keyPrefix = RelationLocks.prefix(cacheType, userId);
        return Arrays.asList(keyPrefix + "lock", keyPrefix + "list", keyPrefix + "meta");
    }

    /** 原子读取指定范围；null 表示缓存尚未初始化或已失效。 */
    public RelationCacheSnapshotDTO read(String cacheType, long userId, long startIndex, long endIndex) {
        List<String> redisKeys = buildCacheKeys(cacheType, userId);
        List<?> scriptResult = stringRedisTemplate.execute(READ_LIST_SCRIPT, redisKeys,
                String.valueOf(startIndex), String.valueOf(endIndex));
        if (Objects.isNull(scriptResult) || scriptResult.isEmpty()) {
            return null;
        }

        // Lua 第一个返回值是整份 ZSet 的成员数，之后按 member、score 成对排列
        long cachedCount = Long.parseLong(scriptResult.get(0).toString());
        List<RelationCacheEntryDTO> cacheEntries = new ArrayList<>();
        for (int index = 1; index < scriptResult.size(); index += 2) {
            long memberUserId = Long.parseLong(scriptResult.get(index).toString());
            long followTimeMillis = Math.round(Double.parseDouble(scriptResult.get(index + 1).toString()));
            cacheEntries.add(new RelationCacheEntryDTO(memberUserId, followTimeMillis));
        }
        return new RelationCacheSnapshotDTO(cachedCount, cacheEntries);
    }

    /** 缓存未命中时加锁初始化。消费者使用同一把锁，避免初始化覆盖其更新。 */
    public RelationCacheSnapshotDTO load(String cacheType, long userId, long startIndex, long endIndex) {
        RelationCacheSnapshotDTO cacheSnapshot = read(cacheType, userId, startIndex, endIndex);
        if (Objects.nonNull(cacheSnapshot)) {
            return cacheSnapshot;
        }

        try (RelationLocks.Handle cacheLock = relationLocks.acquire(cacheType, userId)) {
            // 获取锁后再次检查，避免重复加载主库
            cacheSnapshot = read(cacheType, userId, startIndex, endIndex);
            if (Objects.nonNull(cacheSnapshot)) {
                return cacheSnapshot;
            }

            boolean fansCache = FANS_CACHE.equals(cacheType);
            // 二次确认缓存未命中后才扣额度；粉丝只取最近的 N 条，关注列表有上限、直接全取
            relationDatabaseRateLimiter.acquire();
            List<RelationDO> relations = fansCache
                    ? relationDOMapper.followerPage(userId, 0, relationProperties.getFansCacheSize())
                    : relationDOMapper.followingList(userId);
            List<RelationCacheEntryDTO> cacheEntries = new ArrayList<>(relations.size());
            for (RelationDO relation : relations) {
                cacheEntries.add(buildCacheEntry(relation, fansCache));
            }

            write(cacheType, userId, cacheLock, true, Collections.emptyList(), cacheEntries);
            cacheSnapshot = read(cacheType, userId, startIndex, endIndex);
            if (Objects.isNull(cacheSnapshot)) {
                throw new IllegalStateException("列表缓存初始化后已失效，请重试");
            }
            return cacheSnapshot;
        }
    }

    /**
     * 把一条关系记录转换成缓存条目。
     * 粉丝列表取发起人 ID，关注列表取被关注人 ID；分数统一用关注时间。
     */
    private RelationCacheEntryDTO buildCacheEntry(RelationDO relationDO, boolean fansCache) {
        Long memberUserId = fansCache ? relationDO.getFromUserId() : relationDO.getToUserId();
        return new RelationCacheEntryDTO(memberUserId, score(relationDO.getUpdateTime()));
    }

    /** 判断这条关系记录当前是否为“关注”状态（type=1）。 */
    private boolean isFollowing(RelationDO relationDO) {
        return Objects.nonNull(relationDO)
                && FollowUnfollowTypeEnum.FOLLOW.getCode().equals(relationDO.getType());
    }

    /** 按主库当前状态维护关注缓存；未初始化时不使用单条事件创建列表。 */
    public void syncFollowing(long fromUserId, long toUserId) {
        try (RelationLocks.Handle cacheLock = relationLocks.acquire(FOLLOWING_CACHE, fromUserId)) {
            // 未初始化的列表不在这里创建，交给读路径全量加载，避免造出"只有一个人"的假列表
            if (Objects.isNull(read(FOLLOWING_CACHE, fromUserId, 0, 0))) {
                return;
            }

            // 消息只触发同步，实际状态以主库为准，避免迟到的关注消息恢复已取关用户
            RelationDO followingDO = relationDOMapper.following(fromUserId, toUserId);
            List<RelationCacheEntryDTO> entriesToAdd = isFollowing(followingDO)
                    ? List.of(buildCacheEntry(followingDO, false))
                    : List.of();
            // 先移除旧成员，再按当前状态添加，Lua 中一起完成
            write(FOLLOWING_CACHE, fromUserId, cacheLock, false, List.of(toUserId), entriesToAdd);
        }
    }

    /**
     * 粉丝投影事务提交后调用，调用方必须持有目标用户的粉丝列表锁。
     * 只按主库当前状态增删这一条，不读缓存、也不补人：粉丝列表允许比真实粉丝少，
     * 少掉的位置会在下一次新关注时自然补满，或者等缓存过期后重建。
     */
    public void syncFollowerLocked(long fromUserId, long toUserId, RelationLocks.Handle cacheLock) {
        RelationDO followerDO = relationDOMapper.follower(fromUserId, toUserId);
        List<RelationCacheEntryDTO> entriesToAdd = isFollowing(followerDO)
                ? List.of(buildCacheEntry(followerDO, true))
                : List.of();
        write(FANS_CACHE, toUserId, cacheLock, false, List.of(fromUserId), entriesToAdd);
    }

    /** 构建 Lua 参数并发布列表变更；参数顺序与 relation-list-write.lua 一致。 */
    private void write(String cacheType, long userId, RelationLocks.Handle cacheLock,
                       boolean initialize, List<Long> userIdsToRemove, List<RelationCacheEntryDTO> entriesToAdd) {
        int cacheLimit = FANS_CACHE.equals(cacheType)
                ? relationProperties.getFansCacheSize()
                : Integer.MAX_VALUE;

        List<String> luaArgs = new ArrayList<>();
        luaArgs.add(cacheLock.token());
        luaArgs.add(initialize ? "init" : "delta");
        luaArgs.add(String.valueOf(relationProperties.getCacheTtlSeconds() * 1000));
        luaArgs.add(String.valueOf(cacheLimit));
        luaArgs.add(String.valueOf(userIdsToRemove.size()));
        for (Long memberUserId : userIdsToRemove) {
            luaArgs.add(member(memberUserId));
        }
        for (RelationCacheEntryDTO cacheEntry : entriesToAdd) {
            luaArgs.add(String.valueOf(cacheEntry.getMillis()));
            luaArgs.add(member(cacheEntry.getId()));
        }

        Long scriptResult = stringRedisTemplate.execute(WRITE_LIST_SCRIPT,
                buildCacheKeys(cacheType, userId), luaArgs.toArray());
        if (Objects.isNull(scriptResult) || scriptResult < 0) {
            throw new IllegalStateException("列表缓存写入失败：锁归属已变化");
        }
        // 返回 0 表示缓存已失效，交给下次查询初始化，不发布局部列表
    }

    /**
     * 批量查询关注状态：这批目标用户里，当前用户关注了哪些。
     * 直接按联合索引点查，不加载整份关注列表，也不初始化缓存。
     */
    public Map<Long, Boolean> followingStatuses(long userId, List<Long> targetUserIds) {
        if (targetUserIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> followedUserIds = new HashSet<>(relationDOMapper.followingStatuses(userId, targetUserIds));
        Map<Long, Boolean> statuses = new LinkedHashMap<>();
        for (Long targetUserId : targetUserIds) {
            statuses.put(targetUserId, followedUserIds.contains(targetUserId));
        }
        return statuses;
    }

    /** 粉丝状态按目标 ID 批量查投影表，不依赖列表缓存和额外关系 Hash。 */
    public Map<Long, Boolean> followerStatuses(long userId, List<Long> candidateUserIds) {
        if (candidateUserIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> fansUserIds = new HashSet<>(relationDOMapper.followerStatuses(userId, candidateUserIds));
        Map<Long, Boolean> statuses = new LinkedHashMap<>();
        for (Long candidateUserId : candidateUserIds) {
            statuses.put(candidateUserId, fansUserIds.contains(candidateUserId));
        }
        return statuses;
    }
}
