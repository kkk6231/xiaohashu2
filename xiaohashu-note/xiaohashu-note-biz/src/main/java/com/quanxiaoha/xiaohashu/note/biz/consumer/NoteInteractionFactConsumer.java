package com.quanxiaoha.xiaohashu.note.biz.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import com.quanxiaoha.xiaohashu.count.api.event.NoteInteractionEvent;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteLikeDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteCollectionDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteLikeDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteCollectionDOMapper;
import jakarta.annotation.Resource;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

/** 将互动事件顺序落到事实表，数据库唯一索引防止重复记录。 */
@Component
@Slf4j
@RocketMQMessageListener(topic = NoteInteractionEvent.TOPIC,
        consumerGroup = "note-interaction-facts-v2", consumeMode = ConsumeMode.ORDERLY)
public class NoteInteractionFactConsumer implements RocketMQListener<String>, RocketMQPushConsumerLifecycleListener {
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private NoteLikeDOMapper likeMapper;
    @Resource
    private NoteCollectionDOMapper collectionMapper;

    /** 新消费组从最早可用位置读取；已有消费位点不受影响。 */
    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    }

    /** 永久非法消息记录日志并跳过；数据库异常交给 MQ 重试。 */
    @Override
    public void onMessage(String body) {
        NoteInteractionEvent event;
        try {
            event = objectMapper.readValue(body, NoteInteractionEvent.class);
            if (event == null) {
                throw new IllegalArgumentException("互动事件不能为空");
            }
            event.validate();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            log.error("互动消息格式或参数无效，跳过此消息：{}", exception.getMessage());
            return;
        }
        if ("LIKE".equals(event.getActionType())) {
            saveLike(event);
        } else {
            saveCollection(event);
        }
    }

    /** 写入或取消点赞。 */
    private void saveLike(NoteInteractionEvent event) {
        NoteLikeDO row = NoteLikeDO.builder().userId(event.getUserId()).noteId(event.getNoteId())
                .status(event.getTargetState() ? 1 : 2).createTime(LocalDateTime.now()).build();
        if (event.getTargetState()) likeMapper.insertOrUpdate(row);
        else likeMapper.update2UnlikeByUserIdAndNoteId(row);
    }

    /** 写入或取消收藏。 */
    private void saveCollection(NoteInteractionEvent event) {
        NoteCollectionDO row = NoteCollectionDO.builder().userId(event.getUserId()).noteId(event.getNoteId())
                .status(event.getTargetState() ? 1 : 2).createTime(LocalDateTime.now()).build();
        if (event.getTargetState()) collectionMapper.insertOrUpdate(row);
        else collectionMapper.update2UnCollectByUserIdAndNoteId(row);
    }
}
