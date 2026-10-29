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
        redis.delete(List.of("counter:{note:" + id + "}:data", "counter:{note:" + id + "}:seen",
                "counter:{user:" + id + "}:data", "counter:{user:" + id + "}:seen"));
    }

    @AfterEach
    void close() { if (factory != null) factory.destroy(); }

    private CountChangeDTO note(String event, long delta) {
        return new CountChangeDTO(event, "note", id, Map.of("likeTotal", delta));
    }

    @Test
    void duplicateAndZeroNetBatchRemainIdempotent() {
        service.apply(List.of(note("a", 1), note("a", 1), note("b", -1)));
        service.apply(List.of(note("a", 1)));
        assertEquals(0, service.findNotes(List.of(id)).get(0).getLikeTotal());
        assertEquals(2L, redis.opsForSet().size("counter:{note:" + id + "}:seen"));
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
        String userKey = "counter:{user:" + id + "}:data";
        redis.opsForValue().set(userKey, "broken");
        var author = new CountChangeDTO("a", "user", id, Map.of("likesReceivedTotal", 1L));
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
        service.apply(List.of(new CountChangeDTO("delete", "user", id,
                Map.of("likesReceivedTotal", -10L, "worksTotal", -1L))));
        service.apply(List.of(new CountChangeDTO("publish", "user", id, Map.of("worksTotal", 1L)),
                new CountChangeDTO("likes", "user", id, Map.of("likesReceivedTotal", 10L))));
        var user = service.findUsers(List.of(id)).get(0);
        assertEquals(0, user.getLikesReceivedTotal());
        assertEquals(0, user.getWorksTotal());
    }

    @Test
    void largeIntegerAndMissingDataAreHandled() {
        service.apply(List.of(note("large", 4294967297L)));
        assertEquals(4294967297L, service.findNotes(List.of(id)).get(0).getLikeTotal());
        redis.delete("counter:{note:" + id + "}:data");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> service.findNotes(List.of(id)));
        assertThrows(Exception.class, () -> service.apply(List.of(note("next", 1))));
    }

    @Test
    void uninitializedTargetReturnsZeroWithoutCreatingKeys() {
        assertEquals(0, service.findNotes(List.of(id)).get(0).getLikeTotal());
        assertFalse(redis.hasKey("counter:{note:" + id + "}:data"));
        assertFalse(redis.hasKey("counter:{note:" + id + "}:seen"));
    }

    @Test
    void firstWritesAndBatchReadsCanRunConcurrently() throws Exception {
        List<Long> ids = java.util.stream.LongStream.range(id + 1, id + 65).boxed().toList();
        List<String> keys = ids.stream().flatMap(value -> java.util.stream.Stream.of(
                "counter:{note:" + value + "}:data", "counter:{note:" + value + "}:seen")).toList();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        redis.delete(keys);
        try {
            var writer = executor.submit(() -> {
                start.await();
                for (Long value : ids) {
                    service.apply(List.of(new CountChangeDTO("first", "note", value, Map.of("likeTotal", 1L))));
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
}
