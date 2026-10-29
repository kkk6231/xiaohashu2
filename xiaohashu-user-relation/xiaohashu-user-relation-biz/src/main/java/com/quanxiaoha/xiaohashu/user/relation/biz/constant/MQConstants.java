package com.quanxiaoha.xiaohashu.user.relation.biz.constant;

/**
 * @author: 犬小哈
 * @date: 2024/8/10 13:21
 * @version: v1.0.0
 * @description: MQ 常量
 **/
public interface MQConstants {

    /** 关系变更事件 Topic，沿用 API 层共享的消息契约。 */
    String TOPIC_RELATION_CHANGED = com.quanxiaoha.xiaohashu.user.relation.api.event.RelationEvent.TOPIC;

    /**
     * Topic: 关注、取关共用一个
     */
    String TOPIC_FOLLOW_OR_UNFOLLOW = "FollowUnfollowTopic";

    /**
     * Topic: 关注数计数
     */
    String TOPIC_COUNT_FOLLOWING = "CountFollowingTopic";

    /**
     * Topic: 粉丝数计数
     */
    String TOPIC_COUNT_FANS = "CountFansTopic";

    /**
     * 关注标签
     */
    String TAG_FOLLOW = "Follow";

    /**
     * 取关标签
     */
    String TAG_UNFOLLOW = "Unfollow";
}
