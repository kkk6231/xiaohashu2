package com.quanxiaoha.xiaohashu.count.biz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.xiaohashu.count.api.dto.CountChangeDTO;
import com.quanxiaoha.xiaohashu.count.biz.service.impl.CountServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CountRedisTest {
    private static final String SEEN_BUCKET = "20261104";
    private LettuceConnectionFactory factory;
    private CountServiceImpl service;
    private StringRedisTemplate redis;
    private final long id = 879123L;

    @BeforeEach
    void setup() {
        String port = System.getenv("COUNT_TEST_REDIS_PORT");
        Assumptions.assumeTrue(port != null, "独立测试 Redis 未启动");
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(port));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        service = new CountServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        redis.delete(List.of("counter:{note:" + id + "}", "counter:{note:" + id + "}:initialized",
                "counter:{note:" + id + "}:seen:" + SEEN_BUCKET, "counter:{note:" + id + "}:seen",
                "counter:{note:" + id + "}:seen:20261105",
                "counter:{user:" + id + "}", "counter:{user:" + id + "}:initialized",
                "counter:{user:" + id + "}:seen:" + SEEN_BUCKET, "counter:{user:" + id + "}:seen"));
    }

    @AfterEach
    void close() { if (factory != null) factory.destroy(); }

    private CountChangeDTO note(String event, long delta) {
        return new CountChangeDTO(event, SEEN_BUCKET, "note", id, Map.of("likeTotal", delta));
    }

    @Test
    void duplicateAndZeroNetBatchRemainIdempotent() {
        service.apply(List.of(note("a", 1), note("a", 1), note("b", -1)));
        service.apply(List.of(note("a", 1)));
        assertEquals(0, service.findNotes(List.of(id)).get(0).getLikeTotal());
        String dailySeenKey = "counter:{note:" + id + "}:seen:" + SEEN_BUCKET;
        assertEquals(2L, redis.opsForSet().size(dailySeenKey));
        assertEquals("1", redis.opsForValue().get("counter:{note:" + id + "}:initialized"));
        assertTrue(redis.getExpire(dailySeenKey) > 0);
        assertTrue(redis.getExpire(dailySeenKey) <= 604800);
    }

    @Test
    void internalNegativeValueIsNotClampedDuringWrite() {
        service.apply(List.of(note("remove", -1)));
        assertEquals(0, service.findNotes(List.of(id)).get(0).getLikeTotal());
        service.apply(List.of(note("add", 1)));
        assertEquals(0, service.findNotes(List.of(id)).get(0).getLikeTotal());
    }

    @Test
    void partialTargetFailureResumesWithoutDoubleCounting() {
        String userKey = "counter:{user:" + id + "}";
        redis.opsForValue().set(userKey, "broken");
        var author = new CountChangeDTO("a", SEEN_BUCKET,
                "user", id, Map.of("likesReceivedTotal", 1L));
        assertThrows(Exception.class, () -> service.apply(List.of(note("a", 1), author)));
        assertEquals(1, service.findNotes(List.of(id)).get(0).getLikeTotal());
        redis.delete(userKey);
        service.apply(List.of(note("a", 1), author));
        service.apply(List.of(note("a", 1), author));
        assertEquals(1, service.findNotes(List.of(id)).get(0).getLikeTotal());
        assertEquals(1, service.findUsers(List.of(id)).get(0).getLikesReceivedTotal());
    }

    @Test
    void deletionCanArriveBeforePendingLikes() {
        service.apply(List.of(new CountChangeDTO("delete", SEEN_BUCKET, "user", id,
                Map.of("likesReceivedTotal", -10L, "worksTotal", -1L))));
        service.apply(List.of(new CountChangeDTO("publish", SEEN_BUCKET,
                        "user", id, Map.of("worksTotal", 1L)),
                new CountChangeDTO("likes", SEEN_BUCKET,
                        "user", id, Map.of("likesReceivedTotal", 10L))));
        var user = service.findUsers(List.of(id)).get(0);
        assertEquals(0, user.getLikesReceivedTotal());
        assertEquals(0, user.getWorksTotal());
    }

    @Test
    void largeIntegerAndMissingHashAreHandled() {
        service.apply(List.of(note("large", 4294967297L)));
        assertEquals(4294967297L, service.findNotes(List.of(id)).get(0).getLikeTotal());
        redis.delete("counter:{note:" + id + "}");
        // 已处理记录存在但 Hash 丢失，读时隐藏计数，写时拒绝继续累加。
        assertNull(service.findNotes(List.of(id)).get(0).getLikeTotal());
        assertThrows(Exception.class, () -> service.apply(List.of(note("next", 1))));
    }

    @Test
    void uninitializedTargetReturnsZeroWithoutCreatingKeys() {
        assertEquals(0, service.findNotes(List.of(id)).get(0).getLikeTotal());
        assertFalse(redis.hasKey("counter:{note:" + id + "}"));
        assertFalse(redis.hasKey("counter:{note:" + id + "}:initialized"));
        assertFalse(redis.hasKey("counter:{note:" + id + "}:seen"));
    }

    @Test
    void firstWritesAndBatchReadsCanRunConcurrently() throws Exception {
        List<Long> ids = java.util.stream.LongStream.range(id + 1, id + 65).boxed().toList();
        List<String> keys = new ArrayList<>();
        for (Long value : ids) {
            keys.add("counter:{note:" + value + "}");
            keys.add("counter:{note:" + value + "}:initialized");
            keys.add("counter:{note:" + value + "}:seen:" + SEEN_BUCKET);
            keys.add("counter:{note:" + value + "}:seen");
        }
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        redis.delete(keys);
        try {
            var writer = executor.submit(() -> {
                start.await();
                for (Long value : ids) {
                    service.apply(List.of(new CountChangeDTO("first", SEEN_BUCKET,
                            "note", value, Map.of("likeTotal", 1L))));
                }
                return null;
            });
            var reader = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 100; i++) {
                    for (var count : service.findNotes(ids)) {
                        assertTrue(count.getLikeTotal() == 0 || count.getLikeTotal() == 1);
                    }
                }
                return null;
            });
            start.countDown();
            writer.get(30, java.util.concurrent.TimeUnit.SECONDS);
            reader.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(service.findNotes(ids).stream().allMatch(count -> count.getLikeTotal() == 1));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            redis.delete(keys);
        }
    }
    @Test
    void invalidFieldDoesNotPartiallyApplyBatch() {
        String key = "counter:{note:" + id + "}";
        redis.opsForHash().put(key, "collectTotal", "broken");
        var first = new CountChangeDTO("first", SEEN_BUCKET,
                "note", id, Map.of("likeTotal", 1L));
        var second = new CountChangeDTO("second", SEEN_BUCKET,
                "note", id, Map.of("collectTotal", 1L));
        assertThrows(Exception.class, () -> service.apply(List.of(first, second)));
        assertNull(redis.opsForHash().get(key, "likeTotal"));
        assertFalse(redis.hasKey(key + ":initialized"));
        assertFalse(redis.hasKey(key + ":seen:" + SEEN_BUCKET));
    }

    /** 升级前的永久 seen 仍参与去重，避免旧消息在切换后重复累计。 */
    @Test
    void legacySeenStillDeduplicatesDuringMigration() {
        String targetKey = "counter:{note:" + id + "}";
        redis.opsForHash().put(targetKey, "likeTotal", "1");
        redis.opsForSet().add(targetKey + ":seen", "legacy-event");

        service.apply(List.of(note("legacy-event", 1)));

        assertEquals(1, service.findNotes(List.of(id)).get(0).getLikeTotal());
        assertFalse(redis.hasKey(targetKey + ":seen:" + SEEN_BUCKET));
    }

    /** 不同事件日期写入不同 Set，两个分片都设置自动过期时间。 */
    @Test
    void differentDaysUseIndependentExpiringSets() {
        service.apply(List.of(note("day-one", 1),
                new CountChangeDTO("day-two", "20261105", "note", id, Map.of("likeTotal", 1L))));

        assertEquals(2, service.findNotes(List.of(id)).get(0).getLikeTotal());
        assertEquals(1L, redis.opsForSet().size("counter:{note:" + id + "}:seen:20261104"));
        assertEquals(1L, redis.opsForSet().size("counter:{note:" + id + "}:seen:20261105"));
        assertTrue(redis.getExpire("counter:{note:" + id + "}:seen:20261105") > 0);
    }
}
