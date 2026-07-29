package com.quanxiaoha.xiaohashu.count.biz.domain.dataobject;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 计数 MQ 消费记录。
 *
 * <p>该表用于计数落库消费者的 DB 幂等。通过 consumerType + messageId 唯一索引，
 * 防止同一条落库消息被 RocketMQ 重复投递后重复累加 MySQL 计数。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CountConsumeRecordDO {

    /**
     * 主键 ID。
     */
    private Long id;

    /**
     * 消费者类型，例如 COUNT_FOLLOWING、COUNT_FANS。
     */
    private String consumerType;

    /**
     * 消息幂等 ID。非聚合消息使用 eventId，聚合消息使用 batchId。
     */
    private String messageId;

    /**
     * 消费记录创建时间。
     */
    private LocalDateTime createTime;
}
