package com.quanxiaoha.xiaohashu.user.relation.biz.service.impl;

import com.quanxiaoha.xiaohashu.user.relation.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.user.relation.biz.config.RelationProperties;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject.*;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.mapper.RelationOutboxDOMapper;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.SendResult;
import org.springframework.messaging.Message;
import java.util.List;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.*;

/**
 * @description: 本地消息投递，即时触发和定时扫描共用领取、发送及重试流程。
 */
@Component
@Slf4j
public class RelationOutboxPublisher {

    @Resource
    private RelationOutboxDOMapper relationOutboxDOMapper;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private RelationProperties relationProperties;

    // 有界队列只承担即时投递优化，队列已满时由数据库扫描兜底
    private final ExecutorService publishExecutor = new ThreadPoolExecutor(
            2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256),
            this::createPublishThread, new ThreadPoolExecutor.AbortPolicy());

    /**
     * 创建投递线程，设为守护线程，避免阻塞应用关闭。
     */
    private Thread createPublishThread(Runnable publishTask) {
        Thread publishThread = new Thread(publishTask, "relation-outbox");
        publishThread.setDaemon(true);
        return publishThread;
    }

    /** 事务提交后调用，提交一次异步投递任务。 */
    public void wake(long relationId) {
        try {
            publishExecutor.execute(() -> publish(relationId));
        } catch (RejectedExecutionException exception) {
            // 消息已经持久化，线程池拒绝任务不影响关系操作结果
            log.debug("==> 关系消息投递线程池繁忙，等待定时扫描，relationId: {}", relationId);
        }
    }

    /** 扫描已到重试时间的消息，以及租约过期的发送中消息。 */
    @Scheduled(fixedDelayString = "${relation.scan-delay-millis:1000}")
    public void scan() {
        try {
            List<Long> relationIds = relationOutboxDOMapper.candidates(relationProperties.getDispatchBatchSize());
            for (Long relationId : relationIds) {
                wake(relationId);
            }
        } catch (Exception exception) {
            log.error("==> 扫描关系本地消息失败", exception);
        }
    }

    /**
     * 领取当前关系的最早待发版本，再发送 MQ。
     * 领取事务在发送前结束，避免等待 Broker 时持有数据库锁。
     */
    public void publish(long relationId) {
        try {
            RelationOutboxDO outboxDO = transactionTemplate.execute(transactionStatus -> claimPendingMessage(relationId));
            if (outboxDO == null) {
                return;
            }
            sendClaimedMessage(outboxDO);
        } catch (Exception exception) {
            // 领取或状态回写失败时，不删除本地消息，后续扫描会重新处理
            log.error("==> 关系消息投递失败，relationId: {}", relationId, exception);
        }
    }

    /**
     * 在当前关系下领取最早的一条未发送事件。
     * 先锁关系行让即时投递与多实例扫描串行，再用 claimToken + 租约标记“发送中”；
     * 只能按版本顺序推进，前一条没发出去就不能跳过它领后一条。
     *
     * @return 领取到的事件；没有可发消息或领取失败时返回 null
     */
    private RelationOutboxDO claimPendingMessage(long relationId) {
        // 锁定关系行，使即时投递和多实例扫描串行领取同一关系的消息
        Long lockedRelationId = relationOutboxDOMapper.lockRelation(relationId);
        if (lockedRelationId == null) {
            return null;
        }
        RelationOutboxDO pendingMessage = relationOutboxDOMapper.head(relationId);
        if (pendingMessage == null) {
            return null;
        }

        String claimToken = UUID.randomUUID().toString();
        long leaseMicros = relationProperties.getOutboxLeaseMillis() * 1000;
        int updatedCount = relationOutboxDOMapper.claim(pendingMessage.getEventId(), claimToken, leaseMicros);
        if (updatedCount != 1) {
            // 前序消息仍在发送或尚未到重试时间，不能跳过它领取后一版本
            return null;
        }
        pendingMessage.setClaimToken(claimToken);
        return pendingMessage;
    }

    /**
     * 发送一条已领取的事件，并按 claimToken 回写发送状态。
     * 只有 Broker 明确返回 SEND_OK 才算发送成功；超时、异常一律按“结果不确定”处理，
     * 消息保留等待重试。发送成功后继续唤醒下一条，让同一关系的事件串行推进。
     */
    private void sendClaimedMessage(RelationOutboxDO outboxDO) {
        try {
            Message<String> message = MessageBuilder.withPayload(outboxDO.getPayload())
                    .setHeader("KEYS", String.valueOf(outboxDO.getEventId()))
                    .build();
            String hashKey = String.valueOf(outboxDO.getRelationId());
            SendResult sendResult = rocketMQTemplate.syncSendOrderly(
                    MQConstants.TOPIC_RELATION_CHANGED, message, hashKey, relationProperties.getSendTimeoutMillis());

            // 超时和非 SEND_OK 均按结果不确定处理，不能提前标记发送成功
            if (sendResult == null || sendResult.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("Broker 未确认消息发送成功");
            }
            // 发送成功但回写失败时，扫描器可能再次发送，因此消费者必须支持重复消息
            // claimToken 保证租约过期的旧任务无法修改新持有者的发送状态
            int updatedCount = relationOutboxDOMapper.sent(outboxDO.getEventId(), outboxDO.getClaimToken());
            if (updatedCount == 1) {
                wake(outboxDO.getRelationId());
            }
        } catch (Exception exception) {
            recordSendFailure(outboxDO, exception);
        }
    }

    /**
     * 记录一次发送失败，并按 1、2、4…最长 300 秒的间隔退避。
     * 这里只改状态和下次重试时间：消息永不删除，也不存在“重试够了就丢弃”的分支。
     *
     * @param outboxDO  本次领取到的事件
     * @param exception 发送失败或结果不确定的原因
     */
    private void recordSendFailure(RelationOutboxDO outboxDO, Exception exception) {
        // 重试间隔按 1、2、4……秒增长，保持与已有退避策略一致
        int retryExponent = Math.min(outboxDO.getRetryCount(), 8);
        int retryDelaySeconds = Math.min(300, 1 << retryExponent);
        String errorMessage = exception.toString();
        if (errorMessage.length() > 500) {
            errorMessage = errorMessage.substring(0, 500);
        }
        // 这里只记录发送失败或结果不确定，不删除消息，也不设“重试耗尽即丢弃”的分支
        relationOutboxDOMapper.failed(outboxDO.getEventId(), outboxDO.getClaimToken(), retryDelaySeconds, errorMessage);
        log.warn("==> 关系消息发送结果不确定，保留消息等待重试，eventId: {}", outboxDO.getEventId(), exception);
    }

    /**
     * 应用关闭时停止接收新的投递任务，已提交到线程池的任务继续执行。
     */
    @PreDestroy
    public void close() {
        publishExecutor.shutdown();
    }
}
