package com.quanxiaoha.xiaohashu.auth;

import com.quanxiaoha.framework.common.enums.StatusEnum;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.xiaohashu.auth.constant.RoleConstants;
import com.quanxiaoha.xiaohashu.auth.domain.dataobject.RoleDO;
import com.quanxiaoha.xiaohashu.auth.domain.dataobject.UserDO;
import com.quanxiaoha.xiaohashu.auth.domain.dataobject.UserRoleDO;
import com.quanxiaoha.xiaohashu.auth.domain.mapper.RoleDOMapper;
import com.quanxiaoha.xiaohashu.auth.domain.mapper.UserDOMapper;
import com.quanxiaoha.xiaohashu.auth.domain.mapper.UserRoleDOMapper;
import com.quanxiaoha.xiaohashu.auth.service.impl.UserAccessServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAccessServiceTest {

    private UserDOMapper userDOMapper;
    private RoleDOMapper roleDOMapper;
    private UserRoleDOMapper userRoleDOMapper;
    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private UserAccessServiceImpl userAccessService;

    /**
     * 创建不依赖 Spring 容器的用户权限管理服务。
     */
    @BeforeEach
    void setUp() {
        userDOMapper = mock(UserDOMapper.class);
        roleDOMapper = mock(RoleDOMapper.class);
        userRoleDOMapper = mock(UserRoleDOMapper.class);
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        userAccessService = new UserAccessServiceImpl();
        ReflectionTestUtils.setField(userAccessService, "userDOMapper", userDOMapper);
        ReflectionTestUtils.setField(userAccessService, "roleDOMapper", roleDOMapper);
        ReflectionTestUtils.setField(userAccessService, "userRoleDOMapper", userRoleDOMapper);
        ReflectionTestUtils.setField(userAccessService, "redisTemplate", redisTemplate);
    }

    /**
     * 限制用户时应替换 APP 角色并立即刷新 Redis 缓存。
     */
    @Test
    void shouldReplaceAppRoleWhenRestrictingUser() {
        long userId = 99L;
        prepareExistingUser(userId);
        RoleDO restrictedRole = RoleDO.builder().id(2L)
                .roleKey(RoleConstants.RESTRICTED_USER_ROLE_KEY).build();
        when(roleDOMapper.selectByRoleKey(RoleConstants.RESTRICTED_USER_ROLE_KEY))
                .thenReturn(restrictedRole);
        when(userRoleDOMapper.selectRoleKeysByUserId(userId))
                .thenReturn(List.of(RoleConstants.RESTRICTED_USER_ROLE_KEY));

        userAccessService.restrictUser(userId);

        verify(userRoleDOMapper).deleteAppUserRoles(userId);
        ArgumentCaptor<UserRoleDO> roleCaptor = ArgumentCaptor.forClass(UserRoleDO.class);
        verify(userRoleDOMapper).insertSelective(roleCaptor.capture());
        assertEquals(2L, roleCaptor.getValue().getRoleId());
        verify(valueOperations).set("user:roles:99", "[\"restricted_user\"]");
    }

    /**
     * 恢复用户时应重新绑定普通用户角色。
     */
    @Test
    void shouldRestoreCommonUserRole() {
        long userId = 100L;
        prepareExistingUser(userId);
        RoleDO commonRole = RoleDO.builder().id(1L)
                .roleKey(RoleConstants.COMMON_USER_ROLE_KEY).build();
        when(roleDOMapper.selectByRoleKey(RoleConstants.COMMON_USER_ROLE_KEY))
                .thenReturn(commonRole);
        when(userRoleDOMapper.selectRoleKeysByUserId(userId))
                .thenReturn(List.of(RoleConstants.COMMON_USER_ROLE_KEY));

        userAccessService.restoreUser(userId);

        ArgumentCaptor<UserRoleDO> roleCaptor = ArgumentCaptor.forClass(UserRoleDO.class);
        verify(userRoleDOMapper).insertSelective(roleCaptor.capture());
        assertEquals(1L, roleCaptor.getValue().getRoleId());
        verify(valueOperations).set("user:roles:100", "[\"common_user\"]");
    }

    /**
     * 启用用户时应更新账号状态并恢复角色缓存。
     */
    @Test
    void shouldEnableUserAndRefreshRoles() {
        long userId = 101L;
        prepareExistingUser(userId);
        when(userRoleDOMapper.selectRoleKeysByUserId(userId))
                .thenReturn(List.of(RoleConstants.RESTRICTED_USER_ROLE_KEY));

        userAccessService.enableUser(userId);

        ArgumentCaptor<UserDO> userCaptor = ArgumentCaptor.forClass(UserDO.class);
        verify(userDOMapper).updateByPrimaryKeySelective(userCaptor.capture());
        assertEquals(StatusEnum.ENABLE.getValue(), userCaptor.getValue().getStatus());
        verify(valueOperations).set("user:roles:101", "[\"restricted_user\"]");
    }

    /**
     * 管理不存在的用户时应直接返回业务错误。
     */
    @Test
    void shouldRejectMissingUser() {
        when(userDOMapper.selectByPrimaryKey(404L)).thenReturn(null);

        assertThrows(BizException.class, () -> userAccessService.restrictUser(404L));
    }

    /**
     * 准备一个未删除的有效用户。
     *
     * @param userId 用户 ID
     */
    private void prepareExistingUser(long userId) {
        UserDO user = UserDO.builder().id(userId).isDeleted(false).build();
        when(userDOMapper.selectByPrimaryKey(userId)).thenReturn(user);
    }
}
