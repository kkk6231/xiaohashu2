package com.quanxiaoha.xiaohashu.user.relation.biz.consumer;

import com.quanxiaoha.xiaohashu.user.relation.biz.constant.MQConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.xiaohashu.user.relation.api.event.RelationEvent;
import com.quanxiaoha.xiaohashu.user.relation.biz.service.impl.RelationListCache;
import jakarta.annotation.Resource;
import org.apache.rocketmq.spring.annotation.*;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * @description: 关注缓存消费者，按主库当前关系状态增量更新列表。
 */
@Component
@RocketMQMessageListener(
        topic = MQConstants.TOPIC_RELATION_CHANGED,
        consumerGroup = "relation-following-cache-v1",
        consumeMode = ConsumeMode.ORDERLY)
public class FollowingCacheConsumer implements RocketMQListener<String> {
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private RelationListCache relationListCache;

    /**
     * 顺序消费一条关系变更事件，按主库当前状态增量维护关注列表缓存。
     * 事件只当作触发信号，真正的状态以数据库为准。
     */
    @Override
    public void onMessage(String body) {
        // 解析并校验业务事件，异常向外抛出，由 RocketMQ 重试
        RelationEvent relationEvent;
        try {
            relationEvent = objectMapper.readValue(body, RelationEvent.class);
            relationEvent.validate();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid relation event", exception);
        }
        relationListCache.syncFollowing(relationEvent.getFromUserId(), relationEvent.getToUserId());
    }
}
