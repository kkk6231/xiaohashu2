package com.quanxiaoha.xiaohashu.count.biz.consumer;

import cn.hutool.core.collection.CollUtil;
import com.google.common.util.concurrent.RateLimiter;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.dataobject.CountConsumeRecordDO;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CountConsumeRecordDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.UserCountDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.model.dto.AggregationCountFansMqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * @author: 犬小哈
 * @date: 2024/8/9 11:52
 * @version: v1.0.0
 * @description: 计数: 粉丝数存库
 **/
@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_COUNT_FANS_2_DB, // Group 组
        topic = MQConstants.TOPIC_COUNT_FANS_2_DB // 主题 Topic
        )
@Slf4j
public class CountFans2DBConsumer implements RocketMQListener<String> {

    private static final String CONSUMER_TYPE = "COUNT_FANS";

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

        log.info("## 消费到了 MQ 【计数: 粉丝数入库】, {}...", body);

        if (StringUtils.isBlank(body)) {
            throw new IllegalArgumentException("粉丝数入库 MQ 消息体为空");
        }

        AggregationCountFansMqDTO aggregationCountFansMqDTO = JsonUtils.parseObject(body, AggregationCountFansMqDTO.class);
        if (Objects.isNull(aggregationCountFansMqDTO)) {
            throw new IllegalArgumentException("粉丝数入库 MQ 消息体解析为空");
        }

        String batchId = aggregationCountFansMqDTO.getBatchId();
        if (StringUtils.isBlank(batchId)) {
            throw new IllegalArgumentException("粉丝数入库 MQ batchId 为空");
        }

        Map<Long, Integer> countMap = aggregationCountFansMqDTO.getCountMap();
        if (CollUtil.isEmpty(countMap)) {
            log.info("==> 【计数服务：粉丝数入库】聚合结果为空，batchId: {}", batchId);
            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            try {
                // 消费记录和批量计数更新必须在同一个事务里，避免记录成功但计数失败后无法重试。
                countConsumeRecordDOMapper.insert(CountConsumeRecordDO.builder()
                        .consumerType(CONSUMER_TYPE)
                        .messageId(batchId)
                        .createTime(LocalDateTime.now())
                        .build());

                // 判断数据库中，若目标用户的记录不存在，则插入；若记录已存在，则直接更新。
                countMap.forEach((k, v) -> userCountDOMapper.insertOrUpdateFansTotalByUserId(v, k));
            } catch (DuplicateKeyException ex) {
                // consumer_type + batchId 唯一键冲突表示这批聚合结果已入库，直接返回成功。
                log.info("==> 【计数服务：粉丝数入库】重复批次跳过, batchId: {}", batchId);
            } catch (Exception ex) {
                status.setRollbackOnly();
                log.error("==> 【计数服务：粉丝数入库】消费失败，准备交给 RocketMQ 重试, batchId: {}", batchId, ex);
                throw ex;
            }
        });
    }

}
