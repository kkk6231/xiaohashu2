package com.quanxiaoha.xiaohashu.user.relation.biz.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

@Data
@Component
@EnableScheduling
@ConfigurationProperties(prefix = "relation")
public class RelationProperties {
    /** 关注人数上限 */
    private int maxFollowing = 2000;
    /** 粉丝列表最大缓存人数 */
    private int fansCacheSize = 10000;
    /** 缓存有效期，单位：秒 */
    private long cacheTtlSeconds = 3600;
    /** 列表缓存锁租约，单位：毫秒 */
    private long lockLeaseMillis = 30000;
    /** 锁竞争最多等待时间，单位：毫秒；超时仍明确失败，不能假报成功 */
    private long lockWaitMillis = 200;
    /** 消息领取租约，单位：毫秒 */
    private long outboxLeaseMillis = 30000;
    /** MQ 发送超时，单位：毫秒 */
    private long sendTimeoutMillis = 5000;
    /** 每次扫描的最大关系数 */
    private int dispatchBatchSize = 100;
    /** 每个实例每秒允许的列表初始化和分页回源次数 */
    private double databaseQueryPermitsPerSecond = 50;

    @PostConstruct
    public void validate() {
        if (maxFollowing < 1
                || fansCacheSize < 1
                || cacheTtlSeconds < 1
                || lockLeaseMillis < 3000
                || lockWaitMillis < 0 || lockWaitMillis > 5000
                || sendTimeoutMillis < 1
                || outboxLeaseMillis <= sendTimeoutMillis * 2
                || dispatchBatchSize < 1
                || databaseQueryPermitsPerSecond <= 0) {
            throw new IllegalArgumentException("用户关系服务配置不合法，请检查容量、租约和超时设置");
        }
    }
}
