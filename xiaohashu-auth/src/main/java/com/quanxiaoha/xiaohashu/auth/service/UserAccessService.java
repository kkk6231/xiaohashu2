package com.quanxiaoha.xiaohashu.auth.service;

import com.quanxiaoha.framework.common.response.Response;

/**
 * 用户访问权限管理服务。
 */
public interface UserAccessService {

    /**
     * 将用户设置为受限用户。
     *
     * @param userId 用户 ID
     * @return 处理结果
     */
    Response<?> restrictUser(Long userId);

    /**
     * 将受限用户恢复为普通用户。
     *
     * @param userId 用户 ID
     * @return 处理结果
     */
    Response<?> restoreUser(Long userId);

    /**
     * 禁用用户并使现有登录态失效。
     *
     * @param userId 用户 ID
     * @return 处理结果
     */
    Response<?> disableUser(Long userId);

    /**
     * 重新启用用户。
     *
     * @param userId 用户 ID
     * @return 处理结果
     */
    Response<?> enableUser(Long userId);
}
