package com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject;

import lombok.Data;

/** 本地消息投递使用的数据对象；发送状态与租约由 Mapper 条件更新。 */
@Data
public class RelationOutboxDO {
    /** 事件唯一标识，重复发送时保持不变，供消费者去重。 */
    private Long eventId;
    /** 关联的 Following 主表 ID，也是顺序发送的路由键。 */
    private Long relationId;
    /** 同一关系的业务版本，决定发送先后，不能用 eventId 代替。 */
    private Long relationVersion;
    /** 完整事件 JSON，重试直接使用原消息体；事件类型（关注/取关）已在 payload 里。 */
    private String payload;
    /** 已记录的发送失败次数，用于计算退避时间。 */
    private Integer retryCount;
    /** 当前领取者标识，回写成功或失败状态时必须匹配。 */
    private String claimToken;
}
