package com.quanxiaoha.xiaohashu.count.biz;

import com.quanxiaoha.xiaohashu.count.api.event.NoteInteractionEvent;
import com.quanxiaoha.xiaohashu.count.biz.consumer.NoteInteractionCountConsumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NoteInteractionCountConsumerTest {
    /** 同一事件对笔记和作者使用相同事件键，字段和增量分别映射。 */
    @Test
    void buildsIndependentTargetsForCollect() {
        NoteInteractionEvent event = new NoteInteractionEvent();
        event.setEventId("event-1");
        event.setUserId(1L);
        event.setNoteId(2L);
        event.setAuthorId(3L);
        event.setActionType("COLLECT");
        event.setTargetState(false);
        var changes = NoteInteractionCountConsumer.buildChanges(event);
        assertEquals(2, changes.size());
        assertEquals(changes.get(0).getEventKey(), changes.get(1).getEventKey());
        assertEquals(-1L, changes.get(0).getDeltas().get("collectTotal"));
        assertEquals(-1L, changes.get(1).getDeltas().get("collectsReceivedTotal"));
    }
    /** 坏消息跳过，合法消息仍计数；只有计数写入失败时整批重试。 */
    @Test
    void badMessageDoesNotBlockValidMessage() throws Exception {
        var countService = org.mockito.Mockito.mock(com.quanxiaoha.xiaohashu.count.biz.service.CountService.class);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var consumer = new NoteInteractionCountConsumer().interactionCountConsumer("127.0.0.1:9876", mapper, countService);
        var listener = (org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently) consumer.getMessageListener();
        NoteInteractionEvent event = new NoteInteractionEvent();
        event.setEventId("id"); event.setUserId(1L); event.setNoteId(2L); event.setAuthorId(3L);
        event.setActionType("LIKE"); event.setTargetState(true);
        var invalid = new org.apache.rocketmq.common.message.MessageExt();
        invalid.setBody("not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var valid = new org.apache.rocketmq.common.message.MessageExt();
        valid.setBody(mapper.writeValueAsBytes(event));
        var batch = java.util.List.of(invalid, valid);
        assertEquals(org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.CONSUME_SUCCESS,
                listener.consumeMessage(batch, null));
        org.mockito.Mockito.verify(countService).apply(org.mockito.ArgumentMatchers.argThat(changes -> changes.size() == 2));
        org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
                .when(countService).apply(org.mockito.ArgumentMatchers.anyList());
        assertEquals(org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.RECONSUME_LATER,
                listener.consumeMessage(batch, null));
    }
}
