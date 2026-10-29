package com.quanxiaoha.xiaohashu.user.relation.biz.service.impl;

import com.google.common.util.concurrent.RateLimiter;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.xiaohashu.user.relation.biz.config.RelationProperties;
import com.quanxiaoha.xiaohashu.user.relation.biz.enums.ResponseCodeEnum;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/** 列表初始化与分页回源共用的实例级限流，不对缓存命中扣额度。 */
@Component
public class RelationDatabaseRateLimiter {
    @Resource
    private RelationProperties relationProperties;

    private RateLimiter rateLimiter;

    /**
     * 按配置初始化令牌桶。
     * 限流按服务实例计算：实例数 × 每秒许可数才是总的数据库回源上限。
     */
    @PostConstruct
    public void init() {
        rateLimiter = RateLimiter.create(relationProperties.getDatabaseQueryPermitsPerSecond());
    }

    /**
     * 尝试获取一次回源额度。
     * 拿不到就直接快速失败并提示稍后重试，不让请求排队等待，
     * 避免缓存整体失效时大量请求同时压向数据库。
     */
    public void acquire() {
        if (!rateLimiter.tryAcquire()) {
            throw new BizException(ResponseCodeEnum.RELATION_BUSY);
        }
    }
}
