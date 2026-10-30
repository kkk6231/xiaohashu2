package com.quanxiaoha.xiaohashu.note.biz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.xiaohashu.count.api.event.NoteInteractionEvent;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteLikeDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.service.impl.NoteInteractionPublisher;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NoteInteractionPublisherTest {
    /** 清除登录态，避免影响其他测试。 */
    @AfterEach
    void cleanup() { LoginUserContextHolder.remove(); }

    /** 首次点赞使用服务端事件 ID，并向用户顺序队列同步发送。 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void firstLikeCreatesEventAndSendsInUserOrder() throws Exception {
        NoteInteractionPublisher publisher = new NoteInteractionPublisher();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        org.springframework.data.redis.core.ValueOperations valueOperations = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(zset.size(anyString())).thenReturn(1L);
        when(zset.score(anyString(), any(Object.class))).thenReturn(null);
        NoteDOMapper noteMapper = mock(NoteDOMapper.class);
        NoteDO note = NoteDO.builder().id(2L).creatorId(3L).status(1).visible(0).build();
        when(noteMapper.selectByPrimaryKey(2L)).thenReturn(note);
        NoteLikeDOMapper likeMapper = mock(NoteLikeDOMapper.class);
        when(likeMapper.selectLikedByUserIdAndLimit(1L, 100)).thenReturn(Collections.emptyList());
        when(likeMapper.selectCountByUserIdAndNoteId(1L, 2L)).thenReturn(0);
        RocketMQTemplate rocketMQ = mock(RocketMQTemplate.class);
        SendResult result = mock(SendResult.class);
        when(result.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(rocketMQ.syncSendOrderly(eq(NoteInteractionEvent.TOPIC), any(Message.class), eq("1")))
                .thenReturn(result);
        ReflectionTestUtils.setField(publisher, "redisTemplate", redis);
        ReflectionTestUtils.setField(publisher, "noteMapper", noteMapper);
        ReflectionTestUtils.setField(publisher, "likeMapper", likeMapper);
        ReflectionTestUtils.setField(publisher, "rocketMQTemplate", rocketMQ);
        LoginUserContextHolder.setUserId(1L);
        publisher.submit(2L, "LIKE", true);
        var capture = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rocketMQ).syncSendOrderly(eq(NoteInteractionEvent.TOPIC), capture.capture(), eq("1"));
        NoteInteractionEvent event = new ObjectMapper().readValue((String) capture.getValue().getPayload(), NoteInteractionEvent.class);
        assertNotNull(event.getEventId());
        assertEquals(2L, event.getNoteId());
        assertEquals(3L, event.getAuthorId());
        assertTrue(event.getTargetState());
        when(result.getSendStatus()).thenReturn(SendStatus.FLUSH_DISK_TIMEOUT);
        var unknown = publisher.submit(2L, "LIKE", true);
        assertEquals("UNKNOWN", ((java.util.Map<?, ?>) unknown.getData()).get("status"));
    }
    /** Broker 明确拒绝时请求条件失效；发送超时时保留未知状态。 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void invalidatesOnlyForDefiniteBrokerRejection() {
        NoteInteractionPublisher publisher = new NoteInteractionPublisher();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        org.springframework.data.redis.core.ValueOperations valueOperations = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(zset.score(anyString(), any(Object.class))).thenReturn(1.0);
        NoteDOMapper noteMapper = mock(NoteDOMapper.class);
        when(noteMapper.selectByPrimaryKey(2L)).thenReturn(
                NoteDO.builder().id(2L).creatorId(3L).status(1).visible(0).build());
        RocketMQTemplate rocketMQ = mock(RocketMQTemplate.class);
        ReflectionTestUtils.setField(publisher, "redisTemplate", redis);
        ReflectionTestUtils.setField(publisher, "noteMapper", noteMapper);
        ReflectionTestUtils.setField(publisher, "rocketMQTemplate", rocketMQ);
        LoginUserContextHolder.setUserId(1L);
        org.springframework.messaging.MessagingException rejected = new org.springframework.messaging.MessagingException(
                "rejected", new org.apache.rocketmq.client.exception.MQBrokerException(
                org.apache.rocketmq.common.protocol.ResponseCode.NO_PERMISSION, "no permission"));
        when(rocketMQ.syncSendOrderly(eq(NoteInteractionEvent.TOPIC), any(Message.class), eq("1")))
                .thenThrow(rejected).thenThrow(new org.springframework.messaging.MessagingException("timeout"))
                .thenThrow(new org.springframework.messaging.MessagingException("no route",
                        new org.apache.rocketmq.client.exception.MQClientException("No route info for this topic, test", null)));
        assertThrows(org.springframework.messaging.MessagingException.class, () -> publisher.submit(2L, "LIKE", false));
        long scriptsAfterReject = mockingDetails(redis).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("execute")).count();
        assertEquals(2L, scriptsAfterReject);
        var unknown = publisher.submit(2L, "LIKE", false);
        assertEquals("UNKNOWN", ((java.util.Map<?, ?>) unknown.getData()).get("status"));
        long scriptsAfterTimeout = mockingDetails(redis).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("execute")).count();
        assertEquals(3L, scriptsAfterTimeout);
        assertThrows(org.springframework.messaging.MessagingException.class, () -> publisher.submit(2L, "LIKE", false));
        long scriptsAfterNoRoute = mockingDetails(redis).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("execute")).count();
        assertEquals(5L, scriptsAfterNoRoute);
    }
    /** 数据库尚未追上取消事件时，短期目标状态允许再次点赞。 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void pendingUnlikeOverridesStaleDatabase() {
        NoteInteractionPublisher publisher = new NoteInteractionPublisher();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations zset = mock(ZSetOperations.class);
        org.springframework.data.redis.core.ValueOperations values = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("previous-event:0");
        when(redis.hasKey(anyString())).thenReturn(true);
        NoteDOMapper noteMapper = mock(NoteDOMapper.class);
        when(noteMapper.selectByPrimaryKey(2L)).thenReturn(
                NoteDO.builder().id(2L).creatorId(3L).status(1).visible(0).build());
        NoteLikeDOMapper likeMapper = mock(NoteLikeDOMapper.class);
        RocketMQTemplate rocketMQ = mock(RocketMQTemplate.class);
        SendResult result = mock(SendResult.class);
        when(result.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(rocketMQ.syncSendOrderly(eq(NoteInteractionEvent.TOPIC), any(Message.class), eq("1"))).thenReturn(result);
        ReflectionTestUtils.setField(publisher, "redisTemplate", redis);
        ReflectionTestUtils.setField(publisher, "noteMapper", noteMapper);
        ReflectionTestUtils.setField(publisher, "likeMapper", likeMapper);
        ReflectionTestUtils.setField(publisher, "rocketMQTemplate", rocketMQ);
        LoginUserContextHolder.setUserId(1L);
        publisher.submit(2L, "LIKE", true);
        verify(likeMapper, never()).selectCountByUserIdAndNoteId(anyLong(), anyLong());
        verify(rocketMQ).syncSendOrderly(eq(NoteInteractionEvent.TOPIC), any(Message.class), eq("1"));
    }

    /** Redis 部分写入失败时不发送 MQ，并按版本尝试清理缓存。 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cacheFailureDoesNotSendMessage() {
        NoteInteractionPublisher publisher = new NoteInteractionPublisher();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations zset = mock(ZSetOperations.class);
        org.springframework.data.redis.core.ValueOperations values = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForValue()).thenReturn(values);
        when(zset.score(anyString(), any(Object.class))).thenReturn(1.0);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("redis failure"));
        NoteDOMapper noteMapper = mock(NoteDOMapper.class);
        when(noteMapper.selectByPrimaryKey(2L)).thenReturn(
                NoteDO.builder().id(2L).creatorId(3L).status(1).visible(0).build());
        RocketMQTemplate rocketMQ = mock(RocketMQTemplate.class);
        ReflectionTestUtils.setField(publisher, "redisTemplate", redis);
        ReflectionTestUtils.setField(publisher, "noteMapper", noteMapper);
        ReflectionTestUtils.setField(publisher, "rocketMQTemplate", rocketMQ);
        LoginUserContextHolder.setUserId(1L);
        assertThrows(IllegalStateException.class, () -> publisher.submit(2L, "LIKE", false));
        verifyNoInteractions(rocketMQ);
    }
    /** 数据库仍未更新时，点赞、取消、再点赞均按最近目标状态发送。 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void rapidLikeUnlikeLikeUsesPendingState() throws Exception {
        NoteInteractionPublisher publisher = new NoteInteractionPublisher();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations zset = mock(ZSetOperations.class);
        org.springframework.data.redis.core.ValueOperations values = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null, "first:1", "second:0");
        when(zset.score(anyString(), any(Object.class))).thenReturn(null);
        NoteDOMapper noteMapper = mock(NoteDOMapper.class);
        when(noteMapper.selectByPrimaryKey(2L)).thenReturn(
                NoteDO.builder().id(2L).creatorId(3L).status(1).visible(0).build());
        NoteLikeDOMapper likeMapper = mock(NoteLikeDOMapper.class);
        RocketMQTemplate rocketMQ = mock(RocketMQTemplate.class);
        SendResult result = mock(SendResult.class);
        when(result.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(rocketMQ.syncSendOrderly(eq(NoteInteractionEvent.TOPIC), any(Message.class), eq("1"))).thenReturn(result);
        ReflectionTestUtils.setField(publisher, "redisTemplate", redis);
        ReflectionTestUtils.setField(publisher, "noteMapper", noteMapper);
        ReflectionTestUtils.setField(publisher, "likeMapper", likeMapper);
        ReflectionTestUtils.setField(publisher, "rocketMQTemplate", rocketMQ);
        LoginUserContextHolder.setUserId(1L);
        publisher.submit(2L, "LIKE", true);
        publisher.submit(2L, "LIKE", false);
        publisher.submit(2L, "LIKE", true);
        var capture = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rocketMQ, times(3)).syncSendOrderly(eq(NoteInteractionEvent.TOPIC), capture.capture(), eq("1"));
        ObjectMapper mapper = new ObjectMapper();
        assertTrue(mapper.readValue((String) capture.getAllValues().get(0).getPayload(), NoteInteractionEvent.class).getTargetState());
        assertFalse(mapper.readValue((String) capture.getAllValues().get(1).getPayload(), NoteInteractionEvent.class).getTargetState());
        assertTrue(mapper.readValue((String) capture.getAllValues().get(2).getPayload(), NoteInteractionEvent.class).getTargetState());
    }
    /** 列表已修改但 Bloom 更新失败时，尝试失效本次缓存且不发送消息。 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void bloomFailureAfterListWriteInvalidatesCache() {
        NoteInteractionPublisher publisher = new NoteInteractionPublisher();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations zset = mock(ZSetOperations.class);
        org.springframework.data.redis.core.ValueOperations values = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForValue()).thenReturn(values);
        when(zset.score(anyString(), any(Object.class))).thenReturn(null);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(),
                anyString(), anyString())).thenThrow(new IllegalStateException("bloom failure"));
        NoteDOMapper noteMapper = mock(NoteDOMapper.class);
        when(noteMapper.selectByPrimaryKey(2L)).thenReturn(
                NoteDO.builder().id(2L).creatorId(3L).status(1).visible(0).build());
        NoteLikeDOMapper likeMapper = mock(NoteLikeDOMapper.class);
        RocketMQTemplate rocketMQ = mock(RocketMQTemplate.class);
        ReflectionTestUtils.setField(publisher, "redisTemplate", redis);
        ReflectionTestUtils.setField(publisher, "noteMapper", noteMapper);
        ReflectionTestUtils.setField(publisher, "likeMapper", likeMapper);
        ReflectionTestUtils.setField(publisher, "rocketMQTemplate", rocketMQ);
        LoginUserContextHolder.setUserId(1L);
        assertThrows(IllegalStateException.class, () -> publisher.submit(2L, "LIKE", true));
        verifyNoInteractions(rocketMQ);
        // Bloom 检查、列表写、Bloom 写、条件失效四次脚本调用。
        long scriptCalls = mockingDetails(redis).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("execute")).count();
        assertEquals(4L, scriptCalls);
    }
    /** 明确失败时把上一次短期目标状态传入条件失效脚本。 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void rejectionPreservesPreviousPendingState() {
        NoteInteractionPublisher publisher = new NoteInteractionPublisher();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations values = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("previous:1");
        NoteDOMapper noteMapper = mock(NoteDOMapper.class);
        when(noteMapper.selectByPrimaryKey(2L)).thenReturn(
                NoteDO.builder().id(2L).creatorId(3L).status(1).visible(0).build());
        RocketMQTemplate rocketMQ = mock(RocketMQTemplate.class);
        when(rocketMQ.syncSendOrderly(eq(NoteInteractionEvent.TOPIC), any(Message.class), eq("1")))
                .thenThrow(new org.springframework.messaging.MessagingException("rejected",
                        new org.apache.rocketmq.client.exception.MQBrokerException(
                                org.apache.rocketmq.common.protocol.ResponseCode.NO_PERMISSION, "denied")));
        ReflectionTestUtils.setField(publisher, "redisTemplate", redis);
        ReflectionTestUtils.setField(publisher, "noteMapper", noteMapper);
        ReflectionTestUtils.setField(publisher, "rocketMQTemplate", rocketMQ);
        LoginUserContextHolder.setUserId(1L);
        assertThrows(org.springframework.messaging.MessagingException.class, () -> publisher.submit(2L, "LIKE", false));
        verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(),
                anyString(), eq("previous:1"), eq("60"));
    }
}
