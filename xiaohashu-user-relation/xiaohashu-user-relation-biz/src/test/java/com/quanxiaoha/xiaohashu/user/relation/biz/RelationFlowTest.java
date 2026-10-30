package com.quanxiaoha.xiaohashu.user.relation.biz;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.framework.jackson.config.JacksonAutoConfiguration;
import com.quanxiaoha.xiaohashu.user.relation.api.event.RelationEvent;
import com.quanxiaoha.xiaohashu.user.relation.biz.config.RelationProperties;
import com.quanxiaoha.xiaohashu.user.relation.biz.consumer.FollowUnfollowConsumer;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject.*;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.mapper.*;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.dto.RelationCacheEntryDTO;
import com.quanxiaoha.xiaohashu.user.relation.biz.service.impl.*;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;

/** Opt-in integration tests against a dedicated disposable MySQL schema and Redis instance. */
class RelationFlowTest {
    static JdbcTemplate jdbc;
    static RelationDOMapper relations;
    static RelationOutboxDOMapper outbox;
    static TransactionTemplate tx;
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    static RelationLocks locks;
    static RelationListCache cache;
    static RelationDatabaseRateLimiter databaseRateLimiter;
    static RelationProperties properties;
    static ObjectMapper json;
    RelationOutboxPublisher publisher;
    RelationCommandService commands;

    @BeforeAll
    static void setup() throws Exception {
        String url = System.getenv("RELATION_TEST_MYSQL_URL");
        Assumptions.assumeTrue(
                url != null, "Set RELATION_TEST_MYSQL_URL for disposable integration services");
        assertTrue(url.contains("/relation_refactor_test"));
        var ds = new DriverManagerDataSource(url, "root", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS t_relation_outbox");
        jdbc.execute("DROP TABLE IF EXISTS t_relation_user_lock");
        jdbc.execute("DROP TABLE IF EXISTS t_following");
        jdbc.execute("DROP TABLE IF EXISTS t_follower");
        try (Connection c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/relation-v1.sql"));
        }
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setMapperLocations(
                new ClassPathResource("mapper/RelationDOMapper.xml"),
                new ClassPathResource("mapper/RelationOutboxDOMapper.xml"));
        var sf = factory.getObject();

        var session = new SqlSessionTemplate(sf);
        relations = session.getMapper(RelationDOMapper.class);
        outbox = session.getMapper(RelationOutboxDOMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        connection =
                new LettuceConnectionFactory(
                        "127.0.0.1", Integer.parseInt(System.getenv("RELATION_TEST_REDIS_PORT")));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        properties = new RelationProperties();
        properties.setFansCacheSize(3);
        locks = wire(RelationLocks.class, redis, properties);
        databaseRateLimiter = mock(RelationDatabaseRateLimiter.class);
        cache = wire(RelationListCache.class, redis, relations, locks, properties, databaseRateLimiter);
        json = new JacksonAutoConfiguration().objectMapper();
    }

    @BeforeEach
    void reset() {
        org.mockito.Mockito.reset(databaseRateLimiter);
        jdbc.update("DELETE FROM t_relation_outbox");
        jdbc.update("DELETE FROM t_relation_user_lock");
        jdbc.update("DELETE FROM t_following");
        jdbc.update("DELETE FROM t_follower");
        Set<String> keys = redis.keys("relation:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        publisher = mock(RelationOutboxPublisher.class);
        commands =
                wire(
                        RelationCommandService.class,
                        relations,
                        outbox,
                        tx,
                        locks,
                        publisher,
                        properties,
                        json, jdbc);
    }

    @AfterAll
    static void close() {
        // RelationLocks 现在不再持有续约线程池，无需显式关闭
        if (connection != null) connection.destroy();
    }

    @Test
    void databaseLockAloneEnforcesConcurrentFollowingLimit() throws Exception {
        // 两个真实写请求同时进入，只有数据库行锁负责串行化上限检查。
        int oldLimit = properties.getMaxFollowing();
        properties.setMaxFollowing(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
            for (long target : List.of(20L, 30L)) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        commands.change(10, target, true);
                        return true;
                    } catch (com.quanxiaoha.framework.common.exception.BizException exception) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int successCount = 0;
            for (var result : results) {
                if (result.get(10, java.util.concurrent.TimeUnit.SECONDS)) successCount++;
            }
            assertEquals(1, successCount);
            assertEquals(1, relations.followingCount(10));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_relation_outbox", Integer.class));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
            properties.setMaxFollowing(oldLimit);
        }
    }

    @Test
    void statusQueriesDoNotInitializeListCaches() {
        commands.change(10, 20, true);
        assertTrue(cache.followingStatuses(10, List.of(20L, 30L)).get(20L));
        assertFalse(cache.followingStatuses(10, List.of(30L)).get(30L));
        assertFalse(cache.followerStatuses(20, List.of(10L)).get(10L));
        assertNull(cache.read("following", 10, 0, -1));
        assertNull(cache.read("followers", 20, 0, -1));
    }

    @Test
    void duplicateCommandsProduceOneEventPerTransitionAndStableVersions() {
        commands.change(10, 20, true);
        commands.change(10, 20, true);
        assertEquals(
                1, jdbc.queryForObject("SELECT COUNT(*) FROM t_relation_outbox", Integer.class));
        commands.change(10, 20, false);
        commands.change(10, 20, false);
        commands.change(10, 20, true);
        assertEquals(
                3, jdbc.queryForObject("SELECT COUNT(*) FROM t_relation_outbox", Integer.class));
        assertEquals(3, relations.following(10, 20).getVersion());
        verify(publisher, times(3)).wake(anyLong());
    }

    @Test
    void outboxFailureRollsBackSourceAndDoesNotWakePublisher() {
        RelationOutboxDOMapper broken = mock(RelationOutboxDOMapper.class);
        when(broken.insert(any())).thenThrow(new IllegalStateException("disk unavailable"));
        var command =
                wire(
                        RelationCommandService.class,
                        relations,
                        broken,
                        tx,
                        locks,
                        publisher,
                        properties,
                        json, jdbc);
        assertThrows(IllegalStateException.class, () -> command.change(10, 20, true));
        assertNull(relations.following(10, 20));
        verifyNoInteractions(publisher);
    }

    @Test
    void claimBlocksLaterVersionsAndRejectsExpiredOwnerAcknowledgement() {
        commands.change(10, 20, true);
        commands.change(10, 20, false);
        long id = relations.following(10, 20).getId();
        var first =
                tx.execute(
                        s -> {
                            var h = outbox.head(id);
                            assertEquals(1, outbox.claim(h.getEventId(), "old", 1000000));
                            return h;
                        });
        assertTrue(outbox.candidates(100).isEmpty());
        assertEquals(first.getEventId(), outbox.head(id).getEventId());
        jdbc.update(
                "UPDATE t_relation_outbox SET"
                    + " lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE event_id=?",
                first.getEventId());
        assertEquals(1, outbox.claim(first.getEventId(), "new", 1000000));
        assertEquals(0, outbox.sent(first.getEventId(), "old"));
        assertEquals(1, outbox.sent(first.getEventId(), "new"));
        assertEquals(2, outbox.head(id).getRelationVersion());
    }

    @Test
    void projectionRejectsOldEventButStillRepairsRedisOnReplay() throws Exception {
        commands.change(10, 20, true);
        commands.change(10, 20, false);
        List<String> events =
                jdbc.queryForList(
                        "SELECT payload FROM t_relation_outbox ORDER BY relation_version",
                        String.class);
        var consumer = wire(FollowUnfollowConsumer.class, json, relations, tx, locks, cache);
        consumer.onMessage(events.get(1));
        consumer.onMessage(events.get(0));
        assertEquals(2, relations.follower(10, 20).getType());
        cache.load("followers", 20, 0, -1);
        consumer.onMessage(events.get(1));
        assertEquals(0, cache.read("followers", 20, 0, -1).getCount());
        RelationEvent e = json.readValue(events.get(0), RelationEvent.class);
        assertEquals(relations.following(10, 20).getUpdateTime().getNano() % 1000000, 0);
        assertEquals(
                e.getUpdateTime(),
                json.readValue(json.writeValueAsString(e), RelationEvent.class).getUpdateTime());
    }

    @Test
    void emptyInitializationAndIncrementalUpdatesKeepOriginalExpiry() {
        String meta = RelationLocks.prefix("following", 10) + "meta";
        var empty = cache.load("following", 10, 0, -1);
        assertEquals(0, empty.getCount());
        Object expireBefore = redis.opsForHash().get(meta, "expire_at");
        commands.change(10, 20, true);
        cache.syncFollowing(10, 20);
        var active = cache.read("following", 10, 0, -1);
        assertEquals(1, active.getCount());
        // 增量更新不能续期：expire_at 必须保持不变
        assertEquals(expireBefore, redis.opsForHash().get(meta, "expire_at"));
        commands.change(10, 20, false);
        cache.syncFollowing(10, 20);
        assertEquals(0, cache.read("following", 10, 0, -1).getCount());
        assertNotNull(cache.read("following", 10, 0, -1));
    }

    @Test
    void evictedZsetIsNotRebuiltFromSingleEvent() {
        commands.change(10, 20, true);
        commands.change(10, 30, true);
        cache.load("following", 10, 0, -1);
        redis.delete(RelationLocks.prefix("following", 10) + "list");
        cache.syncFollowing(10, 20);
        assertNull(cache.read("following", 10, 0, -1));
        assertEquals(2, cache.load("following", 10, 0, -1).getCount());
    }

    @Test
    void fansCacheKeepsOnlyMostRecentLimit() {
        var time = LocalDateTime.of(2026, 1, 1, 0, 0);
        for (long id : List.of(2L, 9L, 10L, 100L, 200L)) project(id, 50, 1, 1, time);
        var snapshot = cache.load("followers", 50, 0, -1);
        // 容量 3：只保留关注时间最新的 3 个（时间相同时按用户 ID 倒序）
        assertEquals(
                List.of(200L, 100L, 10L),
                snapshot.getEntries().stream().map(RelationCacheEntryDTO::getId).toList());
        assertEquals(3, snapshot.getCount());
        // 取关一个后不补人：列表允许比真实粉丝少，等新关注或过期重建
        project(100, 50, 2, 2, time.plusSeconds(1));
        try (var lock = locks.acquire("followers", 50)) {
            cache.syncFollowerLocked(100, 50, lock);
        }
        assertEquals(
                List.of(200L, 10L),
                cache.read("followers", 50, 0, -1).getEntries().stream()
                        .map(RelationCacheEntryDTO::getId)
                        .toList());
        assertEquals(2, cache.read("followers", 50, 0, -1).getCount());
    }

    @Test
    void fanStatusReadsDatabaseEvenWhenListCacheIsPartial() {
        var time = LocalDateTime.of(2026, 1, 1, 0, 0);
        for (long id = 1; id <= 4; id++) project(id, 50, 1, 1, time);
        // 容量 3，4 个粉丝 → 缓存里只留最近 3 个
        assertEquals(3, cache.load("followers", 50, 0, -1).getCount());
        assertTrue(cache.followerStatuses(50, List.of(1L)).get(1L));
        assertFalse(cache.followerStatuses(50, List.of(5L)).get(5L));
        project(5, 50, 1, 1, time.plusSeconds(1));
        // 不更新 ZSet，状态查询仍应读取到投影表最新结果。
        assertTrue(cache.followerStatuses(50, List.of(5L)).get(5L));
    }

    @Test
    void lostLockCannotPublishOrDeleteNewOwnersLock() {
        var initial = cache.load("followers", 50, 0, -1);
        project(10, 50, 1, 1, LocalDateTime.of(2026, 1, 1, 0, 0));
        try (var lock = locks.acquire("followers", 50)) {
            // 模拟锁被新持有者接管：写缓存时 Lua 校验 token 不匹配，必须拒绝写入
            redis.opsForValue().set(RelationLocks.prefix("followers", 50) + "lock", "replacement-owner");
            assertThrows(IllegalStateException.class, () -> cache.syncFollowerLocked(10, 50, lock));
        }
        assertEquals(
                "replacement-owner",
                redis.opsForValue().get(RelationLocks.prefix("followers", 50) + "lock"));
        assertEquals(0, cache.read("followers", 50, 0, -1).getCount());
    }

    @Test
    void cacheConsumerDoesNotInitializeAnAbsentList() {
        commands.change(10, 20, true);
        cache.syncFollowing(10, 20);
        assertNull(cache.read("following", 10, 0, -1));
    }

    @Test
    void followerDatabaseCommitThenCacheFailureIsRepairable() throws Exception {
        cache.load("followers", 20, 0, -1);
        commands.change(10, 20, true);
        String body = jdbc.queryForObject("SELECT payload FROM t_relation_outbox", String.class);
        RelationListCache failing = mock(RelationListCache.class);
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(failing)
                .syncFollowerLocked(anyLong(), anyLong(), any());
        var first = wire(FollowUnfollowConsumer.class, json, relations, tx, locks, failing);
        assertThrows(IllegalStateException.class, () -> first.onMessage(body));
        assertEquals(1, relations.follower(10, 20).getType());
        assertEquals(0, cache.read("followers", 20, 0, -1).getCount());
        wire(FollowUnfollowConsumer.class, json, relations, tx, locks, cache).onMessage(body);
        assertEquals(1, cache.read("followers", 20, 0, -1).getCount());
    }

    @Test
    void expiredEmptyCacheCannotBecomePartialList() {
        cache.load("following", 10, 0, -1);
        String meta = RelationLocks.prefix("following", 10) + "meta";
        redis.opsForHash().put(meta, "expire_at", "1");
        commands.change(10, 20, true);
        cache.syncFollowing(10, 20);
        assertNull(cache.read("following", 10, 0, -1));
        assertEquals(1, cache.load("following", 10, 0, -1).getCount());
    }

    @Test
    void coldListsAreRateLimitedBeforeReadingDatabase() {
        RelationDOMapper mapper = mock(RelationDOMapper.class);
        var limitedCache = wire(RelationListCache.class, redis, mapper, locks, properties, databaseRateLimiter);
        doThrow(new com.quanxiaoha.framework.common.exception.BizException(
                com.quanxiaoha.xiaohashu.user.relation.biz.enums.ResponseCodeEnum.RELATION_BUSY))
                .when(databaseRateLimiter).acquire();
        for (String kind : List.of("following", "followers")) {
            assertThrows(com.quanxiaoha.framework.common.exception.BizException.class,
                    () -> limitedCache.load(kind, 50, 0, 1));
            assertNull(cache.read(kind, 50, 0, 1));
        }
        verifyNoInteractions(mapper);
        verify(databaseRateLimiter, times(2)).acquire();
    }

    @Test
    void initializationConsumesOnePermitAndSubsequentHitsConsumeNone() {
        cache.load("following", 10, 0, 1);
        cache.load("following", 10, 0, 1);
        cache.load("followers", 50, 0, 1);
        cache.load("followers", 50, 0, 1);
        verify(databaseRateLimiter, times(2)).acquire();
    }

    @Test
    void cacheFilledBeforeLockAcquisitionDoesNotConsumePermit() {
        var empty = new com.quanxiaoha.xiaohashu.user.relation.biz.model.dto.RelationCacheSnapshotDTO(
                0, List.of());
        RelationDOMapper mapper = mock(RelationDOMapper.class);
        var recheckedCache = spy(wire(RelationListCache.class, redis, mapper, locks, properties, databaseRateLimiter));
        doReturn(null, empty).when(recheckedCache).read("followers", 50, 0, 1);
        assertSame(empty, recheckedCache.load("followers", 50, 0, 1));
        verifyNoInteractions(mapper, databaseRateLimiter);
    }

    private List<Long> fanIds() {
        return cache.read("followers", 50, 0, -1).getEntries().stream()
                .map(RelationCacheEntryDTO::getId).toList();
    }

    private void project(long from, long to, int type, long version, LocalDateTime time) {
        var row = new RelationDO();
        row.setId(from);
        row.setFromUserId(from);
        row.setToUserId(to);
        row.setType(type);
        row.setVersion(version);
        row.setUpdateTime(time);
        relations.project(row);
    }

    private static <T> T wire(Class<T> type, Object... dependencies) {
        try {
            T instance = type.getDeclaredConstructor().newInstance();
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!field.isAnnotationPresent(jakarta.annotation.Resource.class)) continue;
                Object dependency =
                        java.util.Arrays.stream(dependencies)
                                .filter(field.getType()::isInstance)
                                .findFirst()
                                .orElseThrow();
                field.setAccessible(true);
                field.set(instance, dependency);
            }
            return instance;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
