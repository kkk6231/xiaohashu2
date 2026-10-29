package com.quanxiaoha.xiaohashu.count.biz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.xiaohashu.count.api.dto.CountChangeDTO;
import com.quanxiaoha.xiaohashu.count.api.event.NoteCountEvent;
import com.quanxiaoha.xiaohashu.count.biz.service.impl.CountServiceImpl;
import com.quanxiaoha.xiaohashu.user.relation.api.event.RelationEvent;
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
    /** 历史消息的废弃字段可读取，新消息不再输出该字段，事件身份保持不变。 */
    @Test
    void historicalEventsRemainReadableWithoutGeneration() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String eventJson = "{\"eventId\":12,\"countGeneration\":1}";
        NoteCountEvent noteEvent = objectMapper.readValue(eventJson, NoteCountEvent.class);
        RelationEvent relationEvent = objectMapper.readValue(eventJson, RelationEvent.class);
        assertEquals(12L, noteEvent.getEventId());
        assertEquals(12L, relationEvent.getEventId());
        assertFalse(objectMapper.writeValueAsString(noteEvent).contains("countGeneration"));
        assertFalse(objectMapper.writeValueAsString(relationEvent).contains("countGeneration"));
    }

    /** 仅注入 Redis 即可更新固定命名空间；Redis 失败继续向上抛出以触发补偿。 */
    @Test
    void updatesUseRedisOnlyAndPropagateFailure() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        CountServiceImpl countService = new CountServiceImpl();
        ReflectionTestUtils.setField(countService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(countService, "objectMapper", new ObjectMapper());
        CountChangeDTO change = new CountChangeDTO("event:1", "note", 10L, Map.of("likeTotal", 1L));
        List<String> targetKeys = List.of("counter:{note:10}:data", "counter:{note:10}:seen");
        countService.apply(List.of(change));
        verify(redisTemplate).execute(any(RedisScript.class), eq(targetKeys), eq("2"), anyString());
        when(redisTemplate.execute(any(RedisScript.class), eq(targetKeys), eq("2"), anyString()))
                .thenThrow(new IllegalStateException("Redis temporarily unavailable"));
        assertThrows(IllegalStateException.class, () -> countService.apply(List.of(change)));
    }
}
