package com.quanxiaoha.xiaohashu.count.biz;

import com.quanxiaoha.xiaohashu.count.biz.service.impl.CountServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

class CountReadTest {
    @Test
    void batchUsesOneAtomicScriptPerTargetAndPreservesBinaryValues() {
        var redis = mock(StringRedisTemplate.class);
        var connection = mock(RedisConnection.class);
        var scripts = mock(RedisScriptingCommands.class);
        when(connection.scriptingCommands()).thenReturn(scripts);
        byte[] data = ByteBuffer.allocate(16).putLong(4294967297L).putLong(3L).array();
        doAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            assertNull(callback.doInRedis(connection));
            return Arrays.asList(null, data);
        }).when(redis).executePipelined(any(RedisCallback.class), any(RedisSerializer.class));
        var service = new CountServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        var counts = service.findNotes(List.of(10L, 20L));

        assertEquals(10L, counts.get(0).getNoteId());
        assertEquals(0, counts.get(0).getLikeTotal());
        assertEquals(20L, counts.get(1).getNoteId());
        assertEquals(4294967297L, counts.get(1).getLikeTotal());
        assertEquals(3L, counts.get(1).getCollectTotal());
        for (long id : List.of(10L, 20L)) {
            verify(scripts).eval(any(byte[].class), eq(ReturnType.VALUE), eq(2),
                    aryEq(("counter:{note:" + id + "}:data").getBytes(StandardCharsets.UTF_8)),
                    aryEq(("counter:{note:" + id + "}:seen").getBytes(StandardCharsets.UTF_8)));
        }
        // 不再把 GET 和 EXISTS 作为两条独立命令发往 Redis。
        verify(connection, never()).stringCommands();
        verify(connection, never()).keyCommands();
    }
}
