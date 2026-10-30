package com.quanxiaoha.xiaohashu.count.biz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.xiaohashu.count.api.dto.CountChangeDTO;
import com.quanxiaoha.xiaohashu.count.biz.service.impl.CountServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CountContractTest {
    /** 仅注入 Redis 即可更新固定命名空间；Redis 失败继续向上抛出以触发重试。 */
    @Test
    void updatesUseRedisOnlyAndPropagateFailure() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        CountServiceImpl countService = new CountServiceImpl();
        ReflectionTestUtils.setField(countService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(countService, "objectMapper", new ObjectMapper());
        CountChangeDTO change = new CountChangeDTO("event:1", "note", 10L, Map.of("likeTotal", 1L));
        List<String> targetKeys = List.of("counter:{note:10}", "counter:{note:10}:seen");
        countService.apply(List.of(change));
        verify(redisTemplate).execute(any(RedisScript.class), eq(targetKeys), anyString());
        when(redisTemplate.execute(any(RedisScript.class), eq(targetKeys), anyString()))
                .thenThrow(new IllegalStateException("Redis temporarily unavailable"));
        assertThrows(IllegalStateException.class, () -> countService.apply(List.of(change)));
    }
}
