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

    private static final String[] INTERNAL_PATHS = {
            "/user/user/register",
            "/user/user/findByPhone",
            "/user/user/password/update",
            "/user/user/findByIds"
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
     * 对公共路径以外的请求执行登录校验，并继续校验内部接口与业务权限。
     */
    private void checkAuthentication() {
        SaRouter.match("/**")
                .notMatch(PUBLIC_PATHS)
                .check(request -> StpUtil.checkLogin());

        checkInternalPath();
        checkBusinessPermission();
    }

    /**
     * 拒绝从网关访问只供服务间调用的内部接口。
     */
    private void checkInternalPath() {
        checkPermission(PermissionConstants.INTERNAL_ACCESS, INTERNAL_PATHS);
    }

    /**
     * 校验发布、互动、关注和管理接口所需的业务权限。
     */
    private void checkBusinessPermission() {
        checkPermission(PermissionConstants.NOTE_PUBLISH,
                "/note/note/publish", "/note/note/update");
        checkPermission(PermissionConstants.COMMENT_PUBLISH,
                "/comment/comment/publish");
        checkPermission(PermissionConstants.FILE_UPLOAD,
                "/oss/file/upload");
        checkPermission(PermissionConstants.SOCIAL_INTERACT,
                "/note/note/like", "/note/note/collect", "/comment/comment/like");
        checkPermission(PermissionConstants.RELATION_WRITE,
                "/relation/relation/follow");
        checkPermission(PermissionConstants.USER_MANAGE,
                "/auth/admin/**");
    }

    /**
     * 为一组路径逐条注册相同的权限校验。
     *
     * @param permission 权限标识
     * @param paths 需要校验的路径
     */
    private void checkPermission(String permission, String... paths) {
        for (String path : paths) {
            SaRouter.match(path, request -> StpUtil.checkPermission(permission));
        }
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
