package com.quanxiaoha.xiaohashu.user.relation.api.event;

import lombok.Data;

import java.time.LocalDateTime;

/** 关系变更事件。消息可能重复投递，消费方必须按 eventId 去重。 */
@Data
public class RelationEvent {
    public static final String TOPIC = "RelationChangedV1";
    /** 事件唯一标识，重试和补发时保持不变 */
    private Long eventId;
    /** 主表关系 ID */
    private Long relationId;
    /** 关注者 ID */
    private Long fromUserId;
    /** 被关注者 ID */
    private Long toUserId;
    /** 关系状态：1 关注，2 取关 */
    private Integer type;
    /** 同一关系每次有效状态变化后递增 */
    private Long relationVersion;

    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private LocalDateTime updateTime;

    public void validate() {
        if (eventId == null
                || eventId <= 0
                || relationId == null
                || relationId <= 0
                || fromUserId == null
                || fromUserId <= 0
                || toUserId == null
                || toUserId <= 0
                || fromUserId.equals(toUserId)
                || relationVersion == null
                || relationVersion < 1
                || updateTime == null
                || type == null
                || (type != 1 && type != 2)) {
            throw new IllegalArgumentException("关系变更事件字段不合法");
        }
    }
}
