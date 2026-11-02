package com.quanxiaoha.xiaohashu.auth.controller;

import com.quanxiaoha.framework.biz.operationlog.aspect.ApiOperationLog;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.auth.model.vo.user.ManageUserAccessReqVO;
import com.quanxiaoha.xiaohashu.auth.service.UserAccessService;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理员调整用户访问权限的接口。
 */
@RestController
@RequestMapping("/admin/user")
public class UserAccessController {

    @Resource
    private UserAccessService userAccessService;

    /**
     * 限制用户发布、互动和关注等新增行为。
     *
     * @param request 目标用户
     * @return 处理结果
     */
    @PostMapping("/restrict")
    @ApiOperationLog(description = "限制用户权限")
    public Response<?> restrictUser(@Validated @RequestBody ManageUserAccessReqVO request) {
        return userAccessService.restrictUser(request.getUserId());
    }

    /**
     * 恢复受限用户的普通功能权限。
     *
     * @param request 目标用户
     * @return 处理结果
     */
    @PostMapping("/restore")
    @ApiOperationLog(description = "恢复用户权限")
    public Response<?> restoreUser(@Validated @RequestBody ManageUserAccessReqVO request) {
        return userAccessService.restoreUser(request.getUserId());
    }

    /**
     * 禁用用户并使其现有登录态失效。
     *
     * @param request 目标用户
     * @return 处理结果
     */
    @PostMapping("/disable")
    @ApiOperationLog(description = "禁用用户")
    public Response<?> disableUser(@Validated @RequestBody ManageUserAccessReqVO request) {
        return userAccessService.disableUser(request.getUserId());
    }

    /**
     * 重新启用用户。
     *
     * @param request 目标用户
     * @return 处理结果
     */
    @PostMapping("/enable")
    @ApiOperationLog(description = "启用用户")
    public Response<?> enableUser(@Validated @RequestBody ManageUserAccessReqVO request) {
        return userAccessService.enableUser(request.getUserId());
    }
}
