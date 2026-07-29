package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.count.biz.enums.FollowUnfollowTypeEnum;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.CountFollowUnfollowMqDTO;
import com.quanxiaoha.xiaohashu.count.biz.service.UserCountCacheService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * @author: 犬小哈
 * @date: 2024/8/9 11:52
 * @version: v1.0.0
 * @description: 计数: 关注数
 **/
@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_COUNT_FOLLOWING, // Group 组
        topic = MQConstants.TOPIC_COUNT_FOLLOWING // 主题 Topic
        )
@Slf4j
public class CountFollowingConsumer implements RocketMQListener<String> { // TODO: Message

    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private UserCountCacheService userCountCacheService;

    @Value("${xiaohashu.count.idempotent.ttl-minutes:15}")
    private Long idempotentTtlMinutes;

    @Override
    public void onMessage(String body) {
        log.info("## 消费到了 MQ 【计数: 关注数】, {}...", body);

        if (StringUtils.isBlank(body)) {
            throw new IllegalArgumentException("关注数 MQ 消息体为空");
        }

        // 关注数和粉丝数计数场景不同，单个用户无法短时间内关注大量用户，所以无需聚合
        // 直接对 Redis 中的 Hash 进行 +1 或 -1 操作即可

        CountFollowUnfollowMqDTO countFollowUnfollowMqDTO = JsonUtils.parseObject(body, CountFollowUnfollowMqDTO.class);
        if (Objects.isNull(countFollowUnfollowMqDTO)) {
            throw new IllegalArgumentException("关注数 MQ 消息体解析为空");
        }

        // 操作类型：关注 or 取关
        Integer type = countFollowUnfollowMqDTO.getType();
        // 原用户ID
        Long userId = countFollowUnfollowMqDTO.getUserId();
        // 事件 ID
        String eventId = countFollowUnfollowMqDTO.getEventId();

        if (StringUtils.isBlank(eventId)) {
            throw new IllegalArgumentException("关注数 MQ eventId 为空");
        }

        long count;
        if (Objects.equals(type, FollowUnfollowTypeEnum.FOLLOW.getCode())) {
            count = 1;
        } else if (Objects.equals(type, FollowUnfollowTypeEnum.UNFOLLOW.getCode())) {
            count = -1;
        } else {
            throw new IllegalArgumentException("未知关注数操作类型: " + type);
        }

        // 更新 Redis
        String idempotentKey = RedisKeyConstants.buildIdempotentFollowingKey(eventId);
        // Redis 不存在时，基于 t_user_count 完整快照初始化，并把本次关注数增量一起写入，避免旧 DB 快照覆盖本次 MQ 增量。
        Long firstConsumed = userCountCacheService.idempotentInitOrIncrementUserCount(userId, idempotentKey,
                idempotentTtlMinutes * 60, RedisKeyConstants.FIELD_FOLLOWING_TOTAL, count);

        if (Objects.equals(firstConsumed, 0L)) {
            // Redis 幂等只负责防止实时计数重复加减；DB 是否重复入库交给 CountFollowing2DBConsumer 的消费记录表兜底。
            log.info("==> 【计数服务：关注数】Redis 计数已处理，跳过 Redis 增量，继续发送入库 MQ 交给 DB 幂等兜底, eventId: {}", eventId);
        }

        // 发送 MQ, 关注数写库
        // 构建消息对象
        Message<String> message = MessageBuilder.withPayload(body)
                .build();

        // 第三级落库 MQ 保持异步：Redis 承接实时读，MySQL 计数通过异步消费者最终一致。
        rocketMQTemplate.asyncSend(MQConstants.TOPIC_COUNT_FOLLOWING_2_DB, message, new SendCallback() {
            @Override
            public void onSuccess(SendResult sendResult) {
                log.info("==> 【计数服务：关注数入库】MQ 异步发送成功，SendResult: {}", sendResult);
            }

            @Override
            public void onException(Throwable throwable) {
                // V1 保留原有异步形态；发送失败先记录日志，后续由数据对齐或 V2 可靠批处理补偿。
                log.error("==> 【计数服务：关注数入库】MQ 异步发送异常, eventId: {}", eventId, throwable);
            }
        });

    }

}
