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
        CountChangeDTO change = new CountChangeDTO("event:1", "20261104",
                "note", 10L, Map.of("likeTotal", 1L));
        List<String> targetKeys = List.of("counter:{note:10}", "counter:{note:10}:initialized",
                "counter:{note:10}:seen:20261104", "counter:{note:10}:seen");
        countService.apply(List.of(change));
        verify(redisTemplate).execute(any(RedisScript.class), eq(targetKeys), anyString(), eq("604800"));
        when(redisTemplate.execute(any(RedisScript.class), eq(targetKeys), anyString(), eq("604800")))
                .thenThrow(new IllegalStateException("Redis temporarily unavailable"));
        assertThrows(IllegalStateException.class, () -> countService.apply(List.of(change)));
    }

    /** 非法日期分片在访问 Redis 前被拒绝，避免生成无法自动清理的 Key。 */
    @Test
    void rejectsInvalidSeenBucketBeforeRedisAccess() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        CountServiceImpl countService = new CountServiceImpl();
        ReflectionTestUtils.setField(countService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(countService, "objectMapper", new ObjectMapper());
        CountChangeDTO change = new CountChangeDTO("event:1", "20261340",
                "note", 10L, Map.of("likeTotal", 1L));

        assertThrows(IllegalArgumentException.class, () -> countService.apply(List.of(change)));
        verifyNoInteractions(redisTemplate);
    }
}
