package com.quanxiaoha.xiaohashu.auth.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.quanxiaoha.framework.common.enums.StatusEnum;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.auth.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.auth.constant.RoleConstants;
import com.quanxiaoha.xiaohashu.auth.domain.dataobject.RoleDO;
import com.quanxiaoha.xiaohashu.auth.domain.dataobject.UserDO;
import com.quanxiaoha.xiaohashu.auth.domain.dataobject.UserRoleDO;
import com.quanxiaoha.xiaohashu.auth.domain.mapper.RoleDOMapper;
import com.quanxiaoha.xiaohashu.auth.domain.mapper.UserDOMapper;
import com.quanxiaoha.xiaohashu.auth.domain.mapper.UserRoleDOMapper;
import com.quanxiaoha.xiaohashu.auth.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.auth.service.UserAccessService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户访问权限管理服务实现。
 */
@Service
public class UserAccessServiceImpl implements UserAccessService {

    @Resource
    private UserDOMapper userDOMapper;
    @Resource
    private RoleDOMapper roleDOMapper;
    @Resource
    private UserRoleDOMapper userRoleDOMapper;
    @Resource
    private RedisTemplate<String, String> redisTemplate;

    /**
     * 将用户的 APP 角色替换为受限用户角色。
     *
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> restrictUser(Long userId) {
        checkUserExists(userId);
        replaceAppRole(userId, RoleConstants.RESTRICTED_USER_ROLE_KEY);
        refreshUserRoleCache(userId);
        return Response.success();
    }

    /**
     * 将用户的 APP 角色恢复为普通用户角色。
     *
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> restoreUser(Long userId) {
        checkUserExists(userId);
        replaceAppRole(userId, RoleConstants.COMMON_USER_ROLE_KEY);
        refreshUserRoleCache(userId);
        return Response.success();
    }

    /**
     * 禁用用户，删除角色缓存并踢下现有登录态。
     *
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> disableUser(Long userId) {
        checkUserExists(userId);
        updateUserStatus(userId, StatusEnum.DISABLED.getValue());
        redisTemplate.delete(RedisKeyConstants.buildUserRoleKey(userId));
        StpUtil.kickout(userId);
        return Response.success();
    }

    /**
     * 启用用户并恢复其当前角色缓存。
     *
     * @param userId 用户 ID
     * @return 处理结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> enableUser(Long userId) {
        checkUserExists(userId);
        updateUserStatus(userId, StatusEnum.ENABLE.getValue());
        refreshUserRoleCache(userId);
        return Response.success();
    }

    /**
     * 校验目标用户是否存在。
     *
     * @param userId 用户 ID
     */
    private void checkUserExists(Long userId) {
        UserDO user = userDOMapper.selectByPrimaryKey(userId);
        if (user == null || Boolean.TRUE.equals(user.getIsDeleted())) {
            throw new BizException(ResponseCodeEnum.USER_NOT_FOUND);
        }
    }

    /**
     * 替换用户在 APP 端使用的互斥角色，保留管理员等其他角色。
     *
     * @param userId 用户 ID
     * @param roleKey 新角色标识
     */
    private void replaceAppRole(Long userId, String roleKey) {
        RoleDO role = roleDOMapper.selectByRoleKey(roleKey);
        if (role == null) {
            throw new BizException(ResponseCodeEnum.ROLE_NOT_FOUND);
        }

        userRoleDOMapper.deleteAppUserRoles(userId);
        LocalDateTime currentTime = LocalDateTime.now();
        UserRoleDO userRole = UserRoleDO.builder()
                .userId(userId)
                .roleId(role.getId())
                .createTime(currentTime)
                .updateTime(currentTime)
                .isDeleted(false)
                .build();
        userRoleDOMapper.insertSelective(userRole);
    }

    /**
     * 更新用户账号状态。
     *
     * @param userId 用户 ID
     * @param status 目标状态
     */
    private void updateUserStatus(Long userId, Integer status) {
        UserDO user = UserDO.builder()
                .id(userId)
                .status(status)
                .updateTime(LocalDateTime.now())
                .build();
        userDOMapper.updateByPrimaryKeySelective(user);
    }

    /**
     * 根据数据库中的有效角色重建用户角色缓存。
     *
     * @param userId 用户 ID
     */
    private void refreshUserRoleCache(Long userId) {
        List<String> roleKeys = userRoleDOMapper.selectRoleKeysByUserId(userId);
        String redisKey = RedisKeyConstants.buildUserRoleKey(userId);
        redisTemplate.opsForValue().set(redisKey, JsonUtils.toJsonString(roleKeys));
    }
}
