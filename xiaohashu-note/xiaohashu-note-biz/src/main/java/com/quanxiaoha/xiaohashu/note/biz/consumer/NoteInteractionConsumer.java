package com.quanxiaoha.xiaohashu.note.biz.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.NoteInteractionCommandDTO;
import com.quanxiaoha.xiaohashu.note.biz.service.impl.NoteInteractionService;
import com.quanxiaoha.xiaohashu.note.biz.rpc.CountRpcService;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;

@Configuration
@Slf4j
public class NoteInteractionConsumer {
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQPushConsumer interactionConsumer(@Value("${rocketmq.name-server}") String nameServer,
            ObjectMapper objectMapper, NoteInteractionService interactionService, CountRpcService countRpcService) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("note-interaction-v1");
        consumer.setNamesrvAddr(nameServer);
        consumer.setConsumeFromWhere(org.apache.rocketmq.common.consumer.ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(NoteInteractionCommandDTO.TOPIC, "*");
        consumer.setConsumeMessageBatchMaxSize(100);
        // 顺序消费的 -1 使用持续重试语义，不将失败命令移到队尾越过后续操作。
        consumer.setMaxReconsumeTimes(-1);
        consumer.registerMessageListener((MessageListenerOrderly) (messages, context) -> {
            try {
                var commands = new ArrayList<NoteInteractionCommandDTO>();
                for (var message : messages) {
                    commands.add(objectMapper.readValue(message.getBody(), NoteInteractionCommandDTO.class));
                }
                // 先提交互动事实和处理结果，再更新计数。RPC 失败不会回滚已经提交的事实。
                var results = interactionService.execute(commands);
                countRpcService.applyInteractionResults(results);
                // 只有计数也成功才确认；重试会复用数据库结果，并由 Redis 排除重复增量。
                return ConsumeOrderlyStatus.SUCCESS;
            } catch (Exception exception) {
                log.error("互动命令处理失败，保留当前批次重试", exception);
                return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
            }
        });
        return consumer;
    }
}
