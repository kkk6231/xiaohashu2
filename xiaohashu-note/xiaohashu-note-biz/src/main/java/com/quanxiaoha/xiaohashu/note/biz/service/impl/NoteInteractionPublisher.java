package com.quanxiaoha.xiaohashu.note.biz.service.impl;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.NoteInteractionCommandDTO;
import jakarta.annotation.Resource;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class NoteInteractionPublisher {
    @Resource
    private RocketMQTemplate rocketMQTemplate;

    /**
     * 校验并发送一条互动命令，等 Broker 确认后返回 ACCEPTED。
     * 接口不写库：发送失败或结果未知时，由客户端用同一个 requestId 重试，不能生成新 ID 冒充重试。
     */
    public Response<?> submit(String requestId, long noteId, String action, boolean targetState) {
        Long userId = LoginUserContextHolder.getUserId();
        NoteInteractionCommandDTO command = new NoteInteractionCommandDTO(requestId, userId, noteId, action, targetState);
        command.validate();
        var result = rocketMQTemplate.syncSendOrderly(NoteInteractionCommandDTO.TOPIC,
                MessageBuilder.withPayload(JsonUtils.toJsonString(command)).build(), userId + ":" + noteId);
        if (result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("互动命令发送结果未知，请使用原 requestId 重试");
        }
        return Response.success(Map.of("requestId", requestId, "status", "ACCEPTED"));
    }
}
