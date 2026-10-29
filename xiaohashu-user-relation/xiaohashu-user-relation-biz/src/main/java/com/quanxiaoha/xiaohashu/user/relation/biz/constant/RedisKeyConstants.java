package com.quanxiaoha.xiaohashu.user.relation.biz.constant;

/**
 * @author: 犬小哈
 * @date: 2024/5/21 15:04
 * @version: v1.0.0
 * @description: TODO
 **/
public class RedisKeyConstants {

    /** 构建关系缓存前缀，锁、列表和元数据使用相同 Redis Cluster 槽。 */
    public static String buildRelationKeyPrefix(String kind, long userId) {
        return "relation:{" + kind + ":" + userId + "}:";
    }
}
