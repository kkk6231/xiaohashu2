package com.quanxiaoha.xiaohashu.note.biz.service.impl;

import com.quanxiaoha.xiaohashu.count.api.event.NoteCountEvent;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteEventOutboxMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
@EnableScheduling
@Slf4j
public class NoteOutboxPublisher {
    @Resource
    private NoteEventOutboxMapper outboxMapper;
    @Resource
    private RocketMQTemplate rocketMQTemplate;

    /** 领取后再发送，不持有数据库事务等待 Broker；发送结果未知时保留原事件重试。 */
    @Scheduled(fixedDelayString = "${note.outbox.scan-delay-millis:1000}")
    public void scan() {
        for (var row : outboxMapper.candidates()) {
            String token = UUID.randomUUID().toString();
            if (outboxMapper.claim(row.getId(), token) != 1) continue;
            try {
                var result = rocketMQTemplate.syncSend(NoteCountEvent.TOPIC,
                        MessageBuilder.withPayload(row.getPayload()).build(), 3000);
                if (result.getSendStatus() != SendStatus.SEND_OK) {
                    throw new IllegalStateException("Broker 未确认作品计数事件");
                }
                outboxMapper.sent(row.getId(), token);
            } catch (Exception exception) {
                outboxMapper.failed(row.getId(), token);
                log.warn("作品计数事件发送失败，保留事件重试，eventId: {}", row.getId(), exception);
            }
        }
    }
}
