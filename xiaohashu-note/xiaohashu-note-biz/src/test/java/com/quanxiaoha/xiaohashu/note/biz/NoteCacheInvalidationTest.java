package com.quanxiaoha.xiaohashu.note.biz;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteEventOutboxDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteEventOutboxMapper;
import com.quanxiaoha.xiaohashu.note.biz.service.impl.NoteOutboxPublisher;
import com.quanxiaoha.xiaohashu.note.biz.constant.RedisKeyConstants;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.mockito.Mockito.*;

class NoteCacheInvalidationTest {
    /** Redis 删除失败时保留 Outbox，不发送广播、不标记成功。 */
    @Test
    void redisFailureRetainsTaskForRetry() {
        NoteEventOutboxMapper mapper = mock(NoteEventOutboxMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        NoteOutboxPublisher publisher = publisher(mapper, redis, mq);
        when(redis.delete(anyString())).thenThrow(new IllegalStateException("redis unavailable"));
        publisher.scan();
        verify(mapper).failed(eq(7L), anyString());
        verify(mapper, never()).sent(anyLong(), anyString());
        verifyNoInteractions(mq);
    }

    /** 成功路径先删除 Redis、再广播，最后确认任务；再次执行允许重复失效。 */
    @Test
    void successAcknowledgesOnlyAfterBothInvalidations() {
        NoteEventOutboxMapper mapper = mock(NoteEventOutboxMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        NoteOutboxPublisher publisher = publisher(mapper, redis, mq);
        SendResult result = new SendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        when(mq.syncSend(anyString(), (Object) eq(10L))).thenReturn(result);
        publisher.scan();
        var order = inOrder(redis, mq, mapper);
        order.verify(redis).delete(RedisKeyConstants.buildNoteDetailKey(10L));
        order.verify(mq).syncSend(anyString(), (Object) eq(10L));
        order.verify(mapper).sent(eq(7L), anyString());
    }

    /** 模拟投递器从数据库读到已提交的缓存任务。 */
    private NoteOutboxPublisher publisher(NoteEventOutboxMapper mapper, StringRedisTemplate redis, RocketMQTemplate mq) {
        NoteEventOutboxDO task = new NoteEventOutboxDO();
        task.setId(7L);
        task.setNoteId(10L);
        task.setEventType("CACHE_INVALIDATE");
        when(mapper.candidates()).thenReturn(List.of(task));
        when(mapper.claim(eq(7L), anyString())).thenReturn(1);
        NoteOutboxPublisher publisher = new NoteOutboxPublisher();
        ReflectionTestUtils.setField(publisher, "outboxMapper", mapper);
        ReflectionTestUtils.setField(publisher, "redisTemplate", redis);
        ReflectionTestUtils.setField(publisher, "rocketMQTemplate", mq);
        return publisher;
    }
}
