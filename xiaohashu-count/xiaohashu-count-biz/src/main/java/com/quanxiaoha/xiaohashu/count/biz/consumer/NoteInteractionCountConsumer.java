package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import com.quanxiaoha.xiaohashu.count.api.dto.CountChangeDTO;
import com.quanxiaoha.xiaohashu.count.api.event.NoteInteractionEvent;
import com.quanxiaoha.xiaohashu.count.biz.service.CountService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 独立消费互动事件，按事件和目标去重后更新笔记与作者计数。 */
@Configuration
@Slf4j
public class NoteInteractionCountConsumer {
    /** 注册互动计数消费组；失败时保留原事件身份重试。 */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQPushConsumer interactionCountConsumer(@Value("${rocketmq.name-server}") String server,
            ObjectMapper objectMapper, CountService countService) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("counter-note-interaction-v2");
        consumer.setNamesrvAddr(server);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(NoteInteractionEvent.TOPIC, "*");
        consumer.setConsumeMessageBatchMaxSize(100);
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            List<CountChangeDTO> changes = new ArrayList<>();
            for (var message : messages) {
                try {
                    NoteInteractionEvent event = objectMapper.readValue(message.getBody(), NoteInteractionEvent.class);
                    changes.addAll(buildChanges(event));
                } catch (IOException | IllegalArgumentException exception) {
                    log.error("跳过非法互动计数消息，msgId: {}, queueId: {}, offset: {}",
                            message.getMsgId(), message.getQueueId(), message.getQueueOffset(), exception);
                }
            }
            if (changes.isEmpty()) {
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
            try {
                countService.apply(changes);
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch (Exception exception) {
                log.error("互动计数更新失败，等待消息重试", exception);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        });
        return consumer;
    }

    /** 同一事件分别影响笔记和作者，目标间独立去重。 */
    public static List<CountChangeDTO> buildChanges(NoteInteractionEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("互动事件不能为空");
        }
        event.validate();
        long delta = event.getTargetState() ? 1 : -1;
        boolean like = "LIKE".equals(event.getActionType());
        String noteField = like ? "likeTotal" : "collectTotal";
        String authorField = like ? "likesReceivedTotal" : "collectsReceivedTotal";
        String eventKey = "interaction-v2:" + event.getEventId();
        return List.of(new CountChangeDTO(eventKey, "note", event.getNoteId(), Map.of(noteField, delta)),
                new CountChangeDTO(eventKey, "user", event.getAuthorId(), Map.of(authorField, delta)));
    }
}
