package com.quanxiaoha.xiaohashu.note.biz.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteInteractionFailureDO;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.NoteInteractionCommandDTO;
import com.quanxiaoha.xiaohashu.note.biz.service.impl.NoteInteractionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Configuration
@Slf4j
public class NoteInteractionConsumer implements MessageListenerOrderly {
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private NoteInteractionService interactionService;

    /** 注册顺序消费者；数据库等临时故障保留当前批次重试，不越过有效命令。 */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQPushConsumer interactionConsumer(@Value("${rocketmq.name-server}") String nameServer)
            throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("note-interaction-v1");
        consumer.setNamesrvAddr(nameServer);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(NoteInteractionCommandDTO.TOPIC, "*");
        consumer.setConsumeMessageBatchMaxSize(100);
        consumer.setMaxReconsumeTimes(-1);
        consumer.registerMessageListener(this);
        return consumer;
    }

    /** 事实、结果及异常原文提交后确认消息，Redis 计数由独立补偿任务更新。 */
    @Override
    public ConsumeOrderlyStatus consumeMessage(List<MessageExt> messages, ConsumeOrderlyContext context) {
        try {
            List<NoteInteractionCommandDTO> commands = new ArrayList<>();
            List<NoteInteractionFailureDO> failures = new ArrayList<>();
            for (MessageExt message : messages) {
                try {
                    NoteInteractionCommandDTO command = objectMapper.readValue(
                            message.getBody(), NoteInteractionCommandDTO.class);
                    if (command == null) {
                        throw new IllegalArgumentException("互动命令不能为空");
                    }
                    command.validate();
                    commands.add(command);
                } catch (JsonProcessingException | IllegalArgumentException exception) {
                    // 只把反序列化和命令校验错误视为永久非法；SQL、RPC 等异常不能在这里跳过。
                    String reason = exception instanceof JsonProcessingException ? "INVALID_JSON" : "INVALID_COMMAND";
                    failures.add(buildFailure(message, reason));
                }
            }
            interactionService.executeBatch(commands, failures);
            if (!failures.isEmpty()) {
                log.warn("非法互动命令已持久化隔离，条数：{}", failures.size());
            }
            return ConsumeOrderlyStatus.SUCCESS;
        } catch (Exception exception) {
            log.error("互动批次未提交，保留原顺序重试；请检查数据库及事务异常", exception);
            return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
        }
    }

    /** 以消息来源和原文摘要去重，避免重投反复插入异常记录；日志不打印原文。 */
    private NoteInteractionFailureDO buildFailure(MessageExt message, String reasonCode)
            throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String source = message.getTopic() + "|" + message.getMsgId() + "|"
                + message.getQueueId() + "|" + message.getQueueOffset() + "|";
        digest.update(source.getBytes(StandardCharsets.UTF_8));
        if (message.getBody() != null) {
            digest.update(message.getBody());
        }
        NoteInteractionFailureDO failure = new NoteInteractionFailureDO();
        failure.setMessageKey(HexFormat.of().formatHex(digest.digest()));
        failure.setTopic(message.getTopic());
        failure.setBrokerMessageId(message.getMsgId());
        failure.setQueueId(message.getQueueId());
        failure.setQueueOffset(message.getQueueOffset());
        failure.setBody(message.getBody());
        failure.setReasonCode(reasonCode);
        return failure;
    }
}
