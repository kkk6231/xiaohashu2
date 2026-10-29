package com.quanxiaoha.xiaohashu.count.biz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.xiaohashu.count.api.dto.CountChangeDTO;
import com.quanxiaoha.xiaohashu.count.biz.service.impl.CountServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CountGenerationTest {
    /** 快照已包含旧事实，旧事件跨目标重试不能再触碰 Redis。 */
    @Test
    void oldEventsAreAcknowledgedWithoutRedisWrites() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(2L);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CountServiceImpl service = service(jdbc, redis);
        service.apply(List.of(new CountChangeDTO(1L, "old", "note", 10L, Map.of("likeTotal", 1L)),
                new CountChangeDTO(1L, "old", "user", 20L, Map.of("likesReceivedTotal", 1L))));
        verifyNoInteractions(redis);
    }

    /** 缺失代次或未来代次不能被解释为当前代次。 */
    @Test
    void missingAndFutureGenerationsFailBeforeRedis() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(2L);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CountServiceImpl service = service(jdbc, redis);
        assertThrows(IllegalArgumentException.class, () -> service.apply(List.of(
                new CountChangeDTO(null, "missing", "note", 10L, Map.of("likeTotal", 1L)))));
        assertThrows(IllegalArgumentException.class, () -> service.apply(List.of(
                new CountChangeDTO(3L, "future", "note", 10L, Map.of("likeTotal", 1L)))));
        verifyNoInteractions(redis);
    }

    /** 恢复屏障拒绝读取，不允许半成品作为零值展示。 */
    @Test
    void rebuildingBlocksReadsAndWrites() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CountServiceImpl service = service(jdbc, redis);
        assertThrows(Exception.class, () -> service.findNotes(List.of(10L)));
        assertThrows(Exception.class, () -> service.apply(List.of(
                new CountChangeDTO(2L, "new", "note", 10L, Map.of("likeTotal", 1L)))));
        verifyNoInteractions(redis);
    }

    /** 组装不访问外部存储的测试服务。 */
    private CountServiceImpl service(JdbcTemplate jdbc, StringRedisTemplate redis) {
        CountServiceImpl service = new CountServiceImpl();
        ReflectionTestUtils.setField(service, "countJdbcTemplate", jdbc);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        return service;
    }
}
