package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.xiaohashu.count.api.dto.CountChangeDTO;
import com.quanxiaoha.xiaohashu.count.api.event.NoteCountEvent;
import com.quanxiaoha.xiaohashu.count.biz.service.CountService;
import com.quanxiaoha.xiaohashu.user.relation.api.event.RelationEvent;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import lombok.extern.slf4j.Slf4j;
import java.util.*;

@Configuration
@Slf4j
public class CountEventBatchConsumer {
    // 关系事件最多拆为两个目标，100 条消息对应最多 200 条计数变更。
    private static final int CONSUME_BATCH_SIZE = 100;
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQPushConsumer relationCounterConsumer(@Value("${rocketmq.name-server}") String server,
            ObjectMapper mapper, CountService service) throws Exception {
        return create("counter-relation-v1", RelationEvent.TOPIC, server, mapper, service, true);
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQPushConsumer noteCounterConsumer(@Value("${rocketmq.name-server}") String server,
            ObjectMapper mapper, CountService service) throws Exception {
        return create("counter-note-v1", NoteCountEvent.TOPIC, server, mapper, service, false);
    }

    private DefaultMQPushConsumer create(String group, String topic, String server, ObjectMapper mapper,
            CountService service, boolean relationEvent) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(group);
        consumer.setNamesrvAddr(server);
        consumer.setConsumeFromWhere(org.apache.rocketmq.common.consumer.ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(topic, "*");
        consumer.setConsumeMessageBatchMaxSize(CONSUME_BATCH_SIZE);
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            try {
                List<CountChangeDTO> changes = new ArrayList<>();
                for (var message : messages) {
                    if (relationEvent) {
                        changes.addAll(relationChanges(mapper.readValue(message.getBody(), RelationEvent.class)));
                    } else {
                        changes.addAll(noteChanges(mapper.readValue(message.getBody(), NoteCountEvent.class)));
                    }
                }
                // 所有目标写入成功才确认本批消息；失败重投时，Redis 按目标和事件键去重。
                service.apply(changes);
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch (Exception exception) {
                log.error("计数批量更新失败，保留原消息重试，topic: {}", topic, exception);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        });
        return consumer;
    }

    /** 一次关注变更同时影响发起人的关注数和对方的粉丝数。 */
    public static List<CountChangeDTO> relationChanges(RelationEvent event) {
        event.validate();
        long delta = event.getType() == 1 ? 1 : -1;
        String key = "relation:" + event.getEventId();
        return List.of(new CountChangeDTO(event.getCountGeneration(), key, "user", event.getFromUserId(), Map.of("followingTotal", delta)),
                new CountChangeDTO(event.getCountGeneration(), key, "user", event.getToUserId(), Map.of("fansTotal", delta)));
    }

    /** 删除作品按删除事务保存的互动快照扣减作者总数，重试时不重新查询快照。 */
    public static List<CountChangeDTO> noteChanges(NoteCountEvent event) {
        if (event.getEventId() == null || event.getNoteId() == null || event.getAuthorId() == null) {
            throw new IllegalArgumentException("作品计数事件不完整");
        }
        Map<String, Long> deltas;
        if ("NOTE_PUBLISHED".equals(event.getEventType())) {
            deltas = Map.of("worksTotal", 1L);
        } else if ("NOTE_DELETED".equals(event.getEventType())) {
            if (event.getRemovedLikeTotal() < 0 || event.getRemovedCollectTotal() < 0) {
                throw new IllegalArgumentException("删除快照不能为负");
            }
            deltas = Map.of("worksTotal", -1L, "likesReceivedTotal", -event.getRemovedLikeTotal(),
                    "collectsReceivedTotal", -event.getRemovedCollectTotal());
        } else {
            throw new IllegalArgumentException("未知作品计数事件");
        }
        return List.of(new CountChangeDTO(event.getCountGeneration(), "note:" + event.getEventId(), "user", event.getAuthorId(), deltas));
    }
}
