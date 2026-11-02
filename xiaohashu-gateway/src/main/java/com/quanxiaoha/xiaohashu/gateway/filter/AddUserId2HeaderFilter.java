package com.quanxiaoha.xiaohashu.gateway.filter;

import cn.dev33.satoken.stp.StpUtil;
import com.quanxiaoha.framework.common.constant.GlobalConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * @author: 犬小哈
 * @date: 2024/4/9 15:52
 * @version: v1.0.0
 * @description: 转发请求时，将用户 ID 添加到 Header 请求头中，透传给下游服务
 **/
@Component
@Slf4j
public class AddUserId2HeaderFilter implements GlobalFilter {

    /**
     * 删除客户端传入的用户 ID，并在登录状态有效时写入网关确认的用户 ID。
     *
     * @param exchange 当前请求上下文
     * @param chain 网关过滤器链
     * @return 后续过滤器执行结果
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Long loginUserId = getLoginUserId();
        ServerWebExchange newExchange = exchange.mutate()
                .request(builder -> builder.headers(headers -> {
                    headers.remove(GlobalConstants.USER_ID);
                    if (loginUserId != null) {
                        headers.set(GlobalConstants.USER_ID, String.valueOf(loginUserId));
                    }
                }))
                .build();
        return chain.filter(newExchange);
    }

    /**
     * 从 Sa-Token 登录上下文中获取当前用户 ID。
     *
     * @return 已登录用户 ID，匿名请求返回 null
     */
    private Long getLoginUserId() {
        if (!StpUtil.isLogin()) {
            return null;
        }

        Long loginUserId = StpUtil.getLoginIdAsLong();
        log.info("## 当前登录的用户 ID: {}", loginUserId);
        return loginUserId;
    }
}
