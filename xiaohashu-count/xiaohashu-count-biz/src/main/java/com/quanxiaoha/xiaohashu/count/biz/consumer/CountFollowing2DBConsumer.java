package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.google.common.util.concurrent.RateLimiter;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.dataobject.CountConsumeRecordDO;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CountConsumeRecordDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.UserCountDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.enums.FollowUnfollowTypeEnum;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.CountFollowUnfollowMqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * @author: 犬小哈
 * @date: 2024/8/9 11:52
 * @version: v1.0.0
 * @description: 计数: 关注数存库
 **/
@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_COUNT_FOLLOWING_2_DB, // Group 组
        topic = MQConstants.TOPIC_COUNT_FOLLOWING_2_DB // 主题 Topic
        )
@Slf4j
public class CountFollowing2DBConsumer implements RocketMQListener<String> {

    private static final String CONSUMER_TYPE = "COUNT_FOLLOWING";

    @Resource
    private UserCountDOMapper userCountDOMapper;
    @Resource
    private CountConsumeRecordDOMapper countConsumeRecordDOMapper;
    @Resource
    private TransactionTemplate transactionTemplate;

    // 每秒创建 5000 个令牌
    private RateLimiter rateLimiter = RateLimiter.create(5000);

    @Override
    public void onMessage(String body) {
        // 流量削峰：通过获取令牌，如果没有令牌可用，将阻塞，直到获得
        rateLimiter.acquire();

        log.info("## 消费到了 MQ 【计数: 关注数入库】, {}...", body);

        if (StringUtils.isBlank(body)) {
            throw new IllegalArgumentException("关注数入库 MQ 消息体为空");
        }

        CountFollowUnfollowMqDTO countFollowUnfollowMqDTO = JsonUtils.parseObject(body, CountFollowUnfollowMqDTO.class);
        if (Objects.isNull(countFollowUnfollowMqDTO)) {
            throw new IllegalArgumentException("关注数入库 MQ 消息体解析为空");
        }

        // 操作类型：关注 or 取关
        Integer type = countFollowUnfollowMqDTO.getType();
        // 原用户ID
        Long userId = countFollowUnfollowMqDTO.getUserId();
        String eventId = countFollowUnfollowMqDTO.getEventId();
        if (StringUtils.isBlank(eventId)) {
            throw new IllegalArgumentException("关注数入库 MQ eventId 为空");
        }

        // 关注数：关注 +1， 取关 -1
        int count;
        if (Objects.equals(type, FollowUnfollowTypeEnum.FOLLOW.getCode())) {
            count = 1;
        } else if (Objects.equals(type, FollowUnfollowTypeEnum.UNFOLLOW.getCode())) {
            count = -1;
        } else {
            throw new IllegalArgumentException("未知关注数入库操作类型: " + type);
        }

        transactionTemplate.executeWithoutResult(status -> {
            try {
                // 消费记录和计数更新必须在同一个事务里，避免记录成功但计数失败后无法重试。
                countConsumeRecordDOMapper.insert(CountConsumeRecordDO.builder()
                        .consumerType(CONSUMER_TYPE)
                        .messageId(eventId)
                        .createTime(LocalDateTime.now())
                        .build());

                // 若用户计数记录不存在则插入，存在则增量更新 following_total。
                userCountDOMapper.insertOrUpdateFollowingTotalByUserId(count, userId);
            } catch (DuplicateKeyException ex) {
                // consumer_type + message_id 唯一键冲突表示这条事件已入库，直接返回成功。
                log.info("==> 【计数服务：关注数入库】重复消费跳过, eventId: {}", eventId);
            } catch (Exception ex) {
                status.setRollbackOnly();
                log.error("==> 【计数服务：关注数入库】消费失败，准备交给 RocketMQ 重试, eventId: {}", eventId, ex);
                throw ex;
            }
        });
    }

}
