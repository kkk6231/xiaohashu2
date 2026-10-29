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
 * @description: 关注和粉丝列表缓存，负责查询初始化、增量同步和粉丝补齐。
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
        // 顺序必须与 Lua 的 KEYS[1..3] 保持一致，所有 key 使用同一 Redis Cluster 槽
        return Arrays.asList(keyPrefix + "lock", keyPrefix + "list", keyPrefix + "meta");
    }

    /**
     * 原子校验缓存元数据并读取指定范围；null 表示缓存尚未初始化或已失效。
     */
    public RelationCacheSnapshotDTO read(String cacheType, long userId, long startIndex, long endIndex) {
        return read(cacheType, userId, startIndex, endIndex, "");
    }

    /** 增量计算时附带目标成员，和尾部、元数据在同一 Lua 中读取，最多返回三个成员。 */
    private RelationCacheSnapshotDTO read(String cacheType, long userId, long startIndex, long endIndex,
                                          String targetMember) {
        List<String> redisKeys = buildCacheKeys(cacheType, userId);
        List<?> scriptResult = stringRedisTemplate.execute(READ_LIST_SCRIPT, redisKeys,
                String.valueOf(startIndex), String.valueOf(endIndex), targetMember);
        if (Objects.isNull(scriptResult) || scriptResult.isEmpty()) {
            return null;
        }

        // Lua 前三个返回值依次为：是否完整、缓存成员数、固定过期时间
        boolean complete = "1".equals(scriptResult.get(0).toString());
        long cachedCount = Long.parseLong(scriptResult.get(1).toString());
        long expireAt = Long.parseLong(scriptResult.get(2).toString());
        List<RelationCacheEntryDTO> cacheEntries = new ArrayList<>();

        // 后续返回值按 member、score 成对排列
        for (int index = 3; index < scriptResult.size(); index += 2) {
            long memberUserId = Long.parseLong(scriptResult.get(index).toString());
            long followTimeMillis = Math.round(Double.parseDouble(scriptResult.get(index + 1).toString()));
            cacheEntries.add(new RelationCacheEntryDTO(memberUserId, followTimeMillis));
        }
        return new RelationCacheSnapshotDTO(complete, cachedCount, expireAt, cacheEntries);
    }

    /**
     * 缓存未命中时加锁初始化。消费者使用同一把锁，避免初始化覆盖其更新。
     */
    public RelationCacheSnapshotDTO load(String cacheType, long userId, long startIndex, long endIndex) {
        RelationCacheSnapshotDTO cacheSnapshot = read(cacheType, userId, startIndex, endIndex);
        // count 为 0 的完整快照是有效空列表，只有 null 才表示需要初始化
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
            // 二次确认缓存未命中后才扣额度，两类列表初始化与深分页共用限流器。
            relationDatabaseRateLimiter.acquire();
            List<RelationDO> relations;
            int cacheLimit;
            if (fansCache) {
                cacheLimit = relationProperties.getFansCacheSize();
                // 多查询一条，用来判断是否还有未缓存的粉丝
                relations = relationDOMapper.followerPage(userId, 0, cacheLimit + 1);
            } else {
                cacheLimit = Integer.MAX_VALUE;
                relations = relationDOMapper.followingList(userId);
            }

            boolean complete = relations.size() <= cacheLimit;
            List<RelationCacheEntryDTO> cacheEntries = new ArrayList<>();
            int cachedCount = Math.min(relations.size(), cacheLimit);
            for (int index = 0; index < cachedCount; index++) {
                cacheEntries.add(buildCacheEntry(relations.get(index), fansCache));
            }

            write(cacheType, userId, cacheLock, true, complete, 0,
                    Collections.emptyList(), cacheEntries);
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
        long followTimeMillis = score(relationDO.getUpdateTime());
        return new RelationCacheEntryDTO(memberUserId, followTimeMillis);
    }

    /**
     * 判断这条关系记录当前是否为“关注”状态（type=1）。
     */
    private boolean isFollowing(RelationDO relationDO) {
        return Objects.nonNull(relationDO)
                && FollowUnfollowTypeEnum.FOLLOW.getCode().equals(relationDO.getType());
    }

    /** 按主库当前状态维护关注缓存；未初始化时不使用单条事件创建列表。 */
    public void syncFollowing(long fromUserId, long toUserId) {
        try (RelationLocks.Handle cacheLock = relationLocks.acquire(FOLLOWING_CACHE, fromUserId)) {
            RelationCacheSnapshotDTO cacheSnapshot = read(FOLLOWING_CACHE, fromUserId, 0, 0);
            if (Objects.isNull(cacheSnapshot)) {
                return;
            }

            // 消息只触发同步，实际状态以主库为准，避免迟到的关注消息恢复已取关用户
            RelationDO followingDO = relationDOMapper.following(fromUserId, toUserId);
            List<RelationCacheEntryDTO> entriesToAdd = new ArrayList<>();
            if (isFollowing(followingDO)) {
                entriesToAdd.add(buildCacheEntry(followingDO, false));
            }

            // 先移除旧成员，再按当前状态添加，Lua 中一起完成
            List<Long> userIdsToRemove = Collections.singletonList(toUserId);
            write(FOLLOWING_CACHE, fromUserId, cacheLock, false, true,
                    cacheSnapshot.getExpires(), userIdsToRemove, entriesToAdd);
        }
    }

    /**
     * 粉丝投影事务提交后调用，调用方必须持有目标用户的粉丝列表锁。
     * 即使数据库已经处理过当前事件，也必须继续完成缓存修复。
     */
    public void syncFollowerLocked(long fromUserId, long toUserId, RelationLocks.Handle cacheLock) {
        RelationDO followerDO = relationDOMapper.follower(fromUserId, toUserId);
        // 尾部两条足以确定移除目标后的边界，无需把整份粉丝列表读回应用。
        RelationCacheSnapshotDTO cacheSnapshot = read(FANS_CACHE, toUserId, -2, -1, member(fromUserId));
        // 缓存不存在时不单独 ZADD，否则会把单条关系误当作完整粉丝列表
        if (Objects.nonNull(cacheSnapshot)) {
            updateFansList(fromUserId, toUserId, followerDO, cacheSnapshot, cacheLock);
        }

    }

    /**
     * 根据最新的关系状态算粉丝列表要增删什么，并补齐被截断的空缺。
     * 人数以元数据为准，不能用这次读回来的几个成员来推算；
     * 只有“缓存被截断且人数不足”时才从数据库按边界补人。
     */
    private void updateFansList(long fromUserId, long toUserId, RelationDO followerDO,
                               RelationCacheSnapshotDTO cacheSnapshot, RelationLocks.Handle cacheLock) {
        // 快照只包含尾部两条与目标成员；人数以元数据为准，不能使用该 Map 的大小。
        Map<Long, RelationCacheEntryDTO> remainingEntries = new HashMap<>();
        for (RelationCacheEntryDTO cacheEntry : cacheSnapshot.getEntries()) {
            remainingEntries.put(cacheEntry.getId(), cacheEntry);
        }
        long remainingCount = cacheSnapshot.getCount();
        if (remainingEntries.remove(fromUserId) != null) {
            remainingCount--;
        }

        List<RelationCacheEntryDTO> entriesToAdd = new ArrayList<>();
        if (isFollowing(followerDO)) {
            RelationCacheEntryDTO currentEntry = buildCacheEntry(followerDO, true);
            remainingEntries.put(fromUserId, currentEntry);
            remainingCount++;
            entriesToAdd.add(currentEntry);
        }

        int cacheLimit = relationProperties.getFansCacheSize();
        boolean complete = cacheSnapshot.isComplete();
        if (remainingCount > cacheLimit) {
            complete = false;
        }

        // 截断列表出现缺口才补齐，重试时即使目标成员已删除，也要检查缺口
        if (!complete && remainingCount < cacheLimit) {
            int missingCount = (int) (cacheLimit - remainingCount);
            List<RelationDO> followingFans = findFansAfterBoundary(toUserId, remainingEntries, missingCount);
            // 多查的一条用于判断是否仍有未缓存成员，不能只凭列表不足 N 人就标记完整
            complete = followingFans.size() <= missingCount;
            int appendCount = Math.min(missingCount, followingFans.size());
            for (int index = 0; index < appendCount; index++) {
                entriesToAdd.add(buildCacheEntry(followingFans.get(index), true));
            }
        }

        // 删除、补入、裁剪及元数据更新通过同一个 Lua 原子完成
        write(FANS_CACHE, toUserId, cacheLock, false, complete, cacheSnapshot.getExpires(),
                Collections.singletonList(fromUserId), entriesToAdd);
    }

    /** 按剩余列表最末成员的时间和 ID，从主库查询缺口数 + 1 条。 */
    private List<RelationDO> findFansAfterBoundary(long userId,
            Map<Long, RelationCacheEntryDTO> remainingEntries, int missingCount) {
        RelationCacheEntryDTO lastEntry = null;
        for (RelationCacheEntryDTO cacheEntry : remainingEntries.values()) {
            if (Objects.isNull(lastEntry) || isEarlierEntry(cacheEntry, lastEntry)) {
                lastEntry = cacheEntry;
            }
        }
        if (Objects.isNull(lastEntry)) {
            // 没有可用边界时，重新读取最近 N + 1 条
            return relationDOMapper.followerPage(userId, 0, relationProperties.getFansCacheSize() + 1);
        }

        LocalDateTime boundaryTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(lastEntry.getMillis()), ZoneOffset.UTC);
        return relationDOMapper.followerAfter(userId, boundaryTime, lastEntry.getId(), missingCount + 1);
    }

    /**
     * 比较两个缓存条目谁更早：先比时间，时间相同再比 ID。
     * 用于在剩余成员里找“最末一个”，作为从数据库补齐的查询边界。
     */
    private boolean isEarlierEntry(RelationCacheEntryDTO cacheEntry, RelationCacheEntryDTO otherEntry) {
        if (cacheEntry.getMillis() != otherEntry.getMillis()) {
            return cacheEntry.getMillis() < otherEntry.getMillis();
        }
        return cacheEntry.getId() < otherEntry.getId();
    }

    /** 构建 Lua 参数并发布列表变更；参数顺序与 relation-list-write.lua 一致。 */
    private void write(String cacheType, long userId, RelationLocks.Handle cacheLock,
                       boolean initialize, boolean complete, long expectedExpireAt,
                       List<Long> userIdsToRemove, List<RelationCacheEntryDTO> entriesToAdd) {
        int cacheLimit = Integer.MAX_VALUE;
        if (FANS_CACHE.equals(cacheType)) {
            cacheLimit = relationProperties.getFansCacheSize();
        }

        List<String> luaArgs = new ArrayList<>();
        luaArgs.add(cacheLock.token());
        luaArgs.add(initialize ? "init" : "delta");
        luaArgs.add(complete ? "1" : "0");
        luaArgs.add(String.valueOf(expectedExpireAt));
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
        // 按联合索引批量查询目标关系，避免查询一个状态却加载整个列表。
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
