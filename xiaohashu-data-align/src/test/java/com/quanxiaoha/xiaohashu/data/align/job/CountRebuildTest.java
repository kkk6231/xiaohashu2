package com.quanxiaoha.xiaohashu.data.align.job;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.TransactionStatus;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CountRebuildTest {
    /** 快照加载失败不能激活新代次，暂停状态由准备事务独立提交。 */
    @Test
    void failedSnapshotNeverActivatesGeneration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString())).thenReturn(
                Map.of("generation", 1L, "status", "READY"),
                Map.of("generation", 1L, "status", "REBUILDING", "rebuild_id", "r1", "next_generation", 2L));
        when(jdbc.queryForList(contains("t_count_rebuild"), eq("r1"))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("r1"))).thenReturn("PREPARED");
        when(jdbc.queryForList(contains("FROM t_user"), eq(Long.class), eq(0L), eq(200)))
                .thenThrow(new IllegalStateException("snapshot failed"));
        CountRebuildXxlJob job = job(jdbc);
        assertThrows(IllegalStateException.class, () -> job.rebuild("r1"));
        verify(jdbc).update(contains("status='REBUILDING'"), eq("r1"), eq(2L));
        verify(jdbc, never()).update(contains("SET generation="), anyLong());
    }

    /** 同一已完成任务再次触发，不开启另一代，也不重写 Redis。 */
    @Test
    void completedRebuildIsIdempotent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of("generation", 2L, "status", "READY"));
        when(jdbc.queryForList(contains("t_count_rebuild"), eq("r1")))
                .thenReturn(List.of(Map.of("status", "COMPLETED")));
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("r1"))).thenReturn("COMPLETED");
        job(jdbc).rebuild("r1");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    /** 恢复同一任务时覆盖新代次快照，全部写完之后才开放计数。 */
    @Test
    void snapshotUsesNewGenerationAndAbsoluteBinaryValues() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of("generation", 1L,
                "status", "REBUILDING", "rebuild_id", "r1", "next_generation", 2L));
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("r1"))).thenReturn("PREPARED");
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyLong())).thenReturn(3L);
        when(jdbc.queryForList(contains("FROM t_user"), eq(Long.class), eq(0L), eq(200))).thenReturn(List.of(10L));
        when(jdbc.queryForList(contains("FROM t_note"), eq(Long.class), eq(0L), eq(200))).thenReturn(List.of(20L));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        var connection = mock(org.springframework.data.redis.connection.RedisConnection.class);
        var strings = mock(org.springframework.data.redis.connection.RedisStringCommands.class);
        when(connection.stringCommands()).thenReturn(strings);
        doAnswer(invocation -> {
            org.springframework.data.redis.core.RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        }).when(redis).execute(any(org.springframework.data.redis.core.RedisCallback.class));
        CountRebuildXxlJob job = job(jdbc);
        ReflectionTestUtils.setField(job, "redisTemplate", redis);
        job.rebuild("r1");
        var keys = org.mockito.ArgumentCaptor.forClass(byte[].class);
        var values = org.mockito.ArgumentCaptor.forClass(byte[].class);
        var order = inOrder(strings, jdbc);
        order.verify(strings, times(2)).set(keys.capture(), values.capture());
        order.verify(jdbc).update(contains("SET generation="), eq(2L));
        assertEquals("counter:g2:{user:10}:data", new String(keys.getAllValues().get(0), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(40, values.getAllValues().get(0).length);
        assertEquals(16, values.getAllValues().get(1).length);
        assertEquals(3L, java.nio.ByteBuffer.wrap(values.getAllValues().get(1)).getLong());
    }

    /** 执行事务回调以验证控制流程；真实 MySQL 锁行为需独立环境集成验证。 */
    private CountRebuildXxlJob job(JdbcTemplate jdbc) {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(new SimpleTransactionStatus());
            return null;
        }).when(transactions).executeWithoutResult(any());
        CountRebuildXxlJob job = new CountRebuildXxlJob();
        ReflectionTestUtils.setField(job, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(job, "transactionTemplate", transactions);
        ReflectionTestUtils.setField(job, "redisTemplate", mock(StringRedisTemplate.class));
        return job;
    }
}
