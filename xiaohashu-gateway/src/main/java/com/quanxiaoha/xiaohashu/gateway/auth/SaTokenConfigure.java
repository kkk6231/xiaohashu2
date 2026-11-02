package com.quanxiaoha.xiaohashu.gateway.auth;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author: 犬小哈
 * @date: 2024/6/13 14:48
 * @version: v1.0.0
 * @description: [Sa-Token 权限认证] 配置类
 **/
@Configuration
@Slf4j
public class SaTokenConfigure {

    private static final String[] PUBLIC_PATHS = {
            "/auth/login",
            "/auth/verification/code/send",
            "/note/note/detail",
            "/comment/comment/list",
            "/comment/comment/child/list"
    };

    /**
     * 注册 Sa-Token 网关全局过滤器。
     *
     * @return Sa-Token Reactor 过滤器
     */
    @Bean
    public SaReactorFilter getSaReactorFilter() {
        return new SaReactorFilter()
                .addInclude("/**")
                .setAuth(obj -> {
                    log.info("==================> SaReactorFilter, Path: {}", SaHolder.getRequest().getRequestPath());
                    checkAuthentication();
                })
                .setError(this::handleAuthException);
    }

    /**
     * 对公共路径以外的请求执行登录校验，并继续校验发布类接口权限。
     */
    private void checkAuthentication() {
        SaRouter.match("/**")
                .notMatch(PUBLIC_PATHS)
                .check(request -> StpUtil.checkLogin());

        checkPublishPermission();
    }

    /**
     * 校验发布笔记、发布评论和上传文件所需的业务权限。
     */
    private void checkPublishPermission() {
        SaRouter.match("/note/note/publish",
                request -> StpUtil.checkPermission(PermissionConstants.NOTE_PUBLISH));
        SaRouter.match("/comment/comment/publish",
                request -> StpUtil.checkPermission(PermissionConstants.COMMENT_PUBLISH));
        SaRouter.match("/oss/file/upload",
                request -> StpUtil.checkPermission(PermissionConstants.FILE_UPLOAD));
    }

    /**
     * 将鉴权异常交给网关全局异常处理器统一生成响应。
     *
     * @param throwable 鉴权过程中产生的异常
     * @return 此方法始终抛出异常，不会正常返回
     */
    private Object handleAuthException(Throwable throwable) {
        if (throwable instanceof NotLoginException notLoginException) {
            throw notLoginException;
        }
        if (throwable instanceof NotPermissionException notPermissionException) {
            throw notPermissionException;
        }
        if (throwable instanceof NotRoleException notRoleException) {
            throw notRoleException;
        }
        throw new RuntimeException("网关鉴权失败", throwable);
    }
}
