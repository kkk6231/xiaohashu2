package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.github.phantomthief.collection.BufferTrigger;
import com.google.common.collect.Maps;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.count.biz.enums.FollowUnfollowTypeEnum;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.AggregationCountFansMqDTO;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.CountFollowUnfollowMqDTO;
import com.quanxiaoha.xiaohashu.count.biz.service.UserCountCacheService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 粉丝数计数消费者。
 *
 * <p>粉丝数属于高频社交计数，保留原项目的 BufferTrigger 聚合思路：
 * 先把 MQ 消息攒成批次，再按目标用户聚合出净增量，最后更新 Redis 并发送落库 MQ。</p>
 */
@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_COUNT_FANS,
        topic = MQConstants.TOPIC_COUNT_FANS)
@Slf4j
public class CountFansConsumer implements RocketMQListener<String> {

    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private UserCountCacheService userCountCacheService;

    /**
     * JVM 内存聚合缓冲器：最多 1000 条或等待 1 秒触发一次批处理。
     */
    private final BufferTrigger<String> bufferTrigger = BufferTrigger.<String>batchBlocking()
            .bufferSize(50000)
            .batchSize(1000)
            .linger(Duration.ofSeconds(1))
            .setConsumerEx(this::consumeMessage)
            .build();

    @Override
    public void onMessage(String body) {
        // onMessage 只入缓冲区，真正的 Redis 更新和落库 MQ 发送在 consumeMessage 中聚合执行。
        bufferTrigger.enqueue(body);
    }

    private void consumeMessage(List<String> bodys) {
        log.info("==> 聚合粉丝数消息, size: {}", bodys.size());
        log.info("==> 聚合粉丝数消息明细: {}", JsonUtils.toJsonString(bodys));

        List<CountFollowUnfollowMqDTO> countFollowUnfollowMqDTOS = bodys.stream()
                .map(body -> JsonUtils.parseObject(body, CountFollowUnfollowMqDTO.class))
                .toList();

        // 按被关注用户分组，因为 fansTotal 统计的是“目标用户被多少人关注”。
        Map<Long, List<CountFollowUnfollowMqDTO>> groupMap = countFollowUnfollowMqDTOS.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(CountFollowUnfollowMqDTO::getTargetUserId));

        Map<Long, Integer> countMap = Maps.newHashMap();
        for (Map.Entry<Long, List<CountFollowUnfollowMqDTO>> entry : groupMap.entrySet()) {
            int finalCount = 0;
            for (CountFollowUnfollowMqDTO countFollowUnfollowMqDTO : entry.getValue()) {
                FollowUnfollowTypeEnum followUnfollowTypeEnum = FollowUnfollowTypeEnum.valueOf(countFollowUnfollowMqDTO.getType());
                if (Objects.isNull(followUnfollowTypeEnum)) {
                    continue;
                }

                switch (followUnfollowTypeEnum) {
                    case FOLLOW -> finalCount += 1;
                    case UNFOLLOW -> finalCount -= 1;
                }
            }
            countMap.put(entry.getKey(), finalCount);
        }

        log.info("==> 聚合后的粉丝数数据: {}", JsonUtils.toJsonString(countMap));

        countMap.forEach((userId, delta) -> {
            // Redis 不存在时，基于 t_user_count 完整快照初始化，并把本批粉丝数聚合增量一起写入。
            userCountCacheService.initOrIncrementUserCount(userId, RedisKeyConstants.FIELD_FANS_TOTAL, Long.valueOf(delta));
        });

        // batchId 标识这一批聚合结果，2DB 消费者用 consumerType + batchId 做落库幂等。
        AggregationCountFansMqDTO aggregationCountFansMqDTO = AggregationCountFansMqDTO.builder()
                .batchId(UUID.randomUUID().toString().replace("-", ""))
                .countMap(countMap)
                .build();

        Message<String> message = MessageBuilder.withPayload(JsonUtils.toJsonString(aggregationCountFansMqDTO))
                .build();

        rocketMQTemplate.asyncSend(MQConstants.TOPIC_COUNT_FANS_2_DB, message, new SendCallback() {
            @Override
            public void onSuccess(SendResult sendResult) {
                log.info("==> 【计数服务：粉丝数入库】MQ 发送成功, SendResult: {}", sendResult);
            }

            @Override
            public void onException(Throwable throwable) {
                log.error("==> 【计数服务：粉丝数入库】MQ 发送异常", throwable);
            }
        });
    }
}
