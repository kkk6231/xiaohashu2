package com.quanxiaoha.xiaohashu.user.relation.biz.consumer;

import com.quanxiaoha.xiaohashu.user.relation.biz.constant.MQConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.xiaohashu.user.relation.api.event.RelationEvent;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject.*;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.mapper.RelationDOMapper;
import com.quanxiaoha.xiaohashu.user.relation.biz.service.impl.*;
import jakarta.annotation.Resource;
import org.apache.rocketmq.spring.annotation.*;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @description: 关系变更消费者，同步粉丝投影并维护粉丝缓存。
 */
@Component
@RocketMQMessageListener(
        topic = MQConstants.TOPIC_RELATION_CHANGED,
        consumerGroup = "relation-follower-projection-v1",
        consumeMode = ConsumeMode.ORDERLY)
public class FollowUnfollowConsumer implements RocketMQListener<String> {
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private RelationDOMapper relationDOMapper;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    private RelationLocks relationLocks;
    @Resource
    private RelationListCache relationListCache;

    /**
     * 顺序消费一条关系变更事件：先写粉丝投影，再同步粉丝列表缓存。
     * 任一步失败都抛异常交给 MQ 重试，不越序确认；
     * 重试时即使投影已是同一版本，后面的缓存修复也必须继续执行。
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
        try (RelationLocks.Handle cacheLock = relationLocks.acquire("followers", relationEvent.getToUserId())) {
            RelationDO relationDO = new RelationDO();
            relationDO.setId(relationEvent.getRelationId());
            relationDO.setFromUserId(relationEvent.getFromUserId());
            relationDO.setToUserId(relationEvent.getToUserId());
            relationDO.setType(relationEvent.getType());
            relationDO.setVersion(relationEvent.getRelationVersion());
            relationDO.setUpdateTime(relationEvent.getUpdateTime());
            // 先提交粉丝投影，再更新缓存；重试时仍需执行缓存步骤
            transactionTemplate.executeWithoutResult(transactionStatus -> relationDOMapper.project(relationDO));
            // 不能因为 Follower 已是相同版本就提前确认消息：上次可能只完成了数据库提交
            relationListCache.syncFollowerLocked(relationEvent.getFromUserId(), relationEvent.getToUserId(), cacheLock);
        }
    }
}
