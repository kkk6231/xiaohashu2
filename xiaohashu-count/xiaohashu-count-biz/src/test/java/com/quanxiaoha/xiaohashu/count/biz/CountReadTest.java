package com.quanxiaoha.xiaohashu.count.biz;

import com.quanxiaoha.xiaohashu.count.biz.service.impl.CountServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CountReadTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void batchReadsHashFieldsAndPreservesInputOrder() {
        var redis = mock(StringRedisTemplate.class);
        var operations = mock(RedisOperations.class);
        var hashOperations = mock(HashOperations.class);
        when(redis.executePipelined(any(SessionCallback.class))).thenAnswer(invocation -> {
            SessionCallback<?> callback = invocation.getArgument(0);
            when(operations.opsForHash()).thenReturn(hashOperations);
            assertNull(callback.execute(operations));
            verify(operations, times(2)).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), eq("likeTotal"), eq("collectTotal"));
            // 第一个目标缺失时 HMGET 返回 null 列表；第二个目标正常返回字段值。
            return List.of(List.of("ok", "0", "0"), List.of("ok", "4294967297", "3"));
        });
        var service = new CountServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        var counts = service.findNotes(List.of(10L, 20L));

        assertEquals(10L, counts.get(0).getNoteId());
        assertEquals(0, counts.get(0).getLikeTotal());
        assertEquals(0, counts.get(0).getCollectTotal());
        assertEquals(20L, counts.get(1).getNoteId());
        assertEquals(4294967297L, counts.get(1).getLikeTotal());
        assertEquals(3L, counts.get(1).getCollectTotal());
    }
    @Test
    void missingDataReturnsNullCounts() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.executePipelined(any(SessionCallback.class))).thenReturn(List.of(List.of("missing")));
        CountServiceImpl service = new CountServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        var count = service.findNotes(List.of(10L)).get(0);
        assertNull(count.getLikeTotal());
        assertNull(count.getCollectTotal());
    }
}
