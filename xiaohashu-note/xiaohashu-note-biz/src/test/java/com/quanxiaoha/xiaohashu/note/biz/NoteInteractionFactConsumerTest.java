package com.quanxiaoha.xiaohashu.note.biz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.xiaohashu.count.api.event.NoteInteractionEvent;
import com.quanxiaoha.xiaohashu.note.biz.consumer.NoteInteractionFactConsumer;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteLikeDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteLikeDOMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NoteInteractionFactConsumerTest {
    /** 点赞和取消按事件目标状态写入同一个事实表。 */
    @Test
    void writesLikeAndUnlike() throws Exception {
        NoteInteractionFactConsumer consumer = new NoteInteractionFactConsumer();
        NoteLikeDOMapper mapper = mock(NoteLikeDOMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(consumer, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(consumer, "likeMapper", mapper);
        NoteInteractionEvent event = new NoteInteractionEvent();
        event.setEventId("event"); event.setUserId(1L); event.setNoteId(2L); event.setAuthorId(3L);
        event.setActionType("LIKE"); event.setTargetState(true);
        consumer.onMessage(objectMapper.writeValueAsString(event));
        event.setTargetState(false);
        consumer.onMessage(objectMapper.writeValueAsString(event));
        verify(mapper).insertOrUpdate(argThat((NoteLikeDO row) -> row.getStatus() == 1));
        verify(mapper).update2UnlikeByUserIdAndNoteId(argThat((NoteLikeDO row) -> row.getStatus() == 2));
    }
    /** 非法消息不触发事实写入，数据库错误则继续抛出以便 MQ 重试。 */
    @Test
    void skipsInvalidButRetriesDatabaseFailure() throws Exception {
        NoteInteractionFactConsumer consumer = new NoteInteractionFactConsumer();
        NoteLikeDOMapper mapper = mock(NoteLikeDOMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(consumer, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(consumer, "likeMapper", mapper);
        consumer.onMessage("not-json");
        consumer.onMessage("{}");
        verifyNoInteractions(mapper);
        NoteInteractionEvent event = new NoteInteractionEvent();
        event.setEventId("id"); event.setUserId(1L); event.setNoteId(2L); event.setAuthorId(3L);
        event.setActionType("LIKE"); event.setTargetState(true);
        when(mapper.insertOrUpdate(any())).thenThrow(new IllegalStateException("database unavailable"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> consumer.onMessage(objectMapper.writeValueAsString(event)));
    }

    /** 事实消费组首次启动从最早可用位点读取。 */
    @Test
    void configuresFirstOffset() {
        NoteInteractionFactConsumer consumer = new NoteInteractionFactConsumer();
        org.apache.rocketmq.client.consumer.DefaultMQPushConsumer mq =
                mock(org.apache.rocketmq.client.consumer.DefaultMQPushConsumer.class);
        consumer.prepareStart(mq);
        verify(mq).setConsumeFromWhere(org.apache.rocketmq.common.consumer.ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    }
}
