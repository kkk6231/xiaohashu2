package com.quanxiaoha.xiaohashu.count.biz.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 粉丝数聚合后的落库消息。
 *
 * <p>CountFansConsumer 将多条关注/取关计数消息按目标用户聚合后，
 * 通过该 DTO 发送给 CountFans2DBConsumer 异步落库。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AggregationCountFansMqDTO {

    /**
     * 聚合批次 ID，用于 CountFans2DBConsumer 做 DB 入库幂等。
     */
    private String batchId;

    /**
     * key 为被关注用户 ID，value 为该批次聚合后的粉丝数净增量。
     */
    private Map<Long, Integer> countMap;
}
