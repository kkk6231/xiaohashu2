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

    @PostConstruct
    public void init() {
        rateLimiter = RateLimiter.create(relationProperties.getDatabaseQueryPermitsPerSecond());
    }

    public void acquire() {
        if (!rateLimiter.tryAcquire()) {
            throw new BizException(ResponseCodeEnum.RELATION_BUSY);
        }
    }
}
