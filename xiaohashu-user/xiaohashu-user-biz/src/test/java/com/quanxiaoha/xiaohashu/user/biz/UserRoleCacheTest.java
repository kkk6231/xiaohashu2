package com.quanxiaoha.xiaohashu.user.biz;

import com.quanxiaoha.xiaohashu.user.biz.domain.dataobject.UserDO;
import com.quanxiaoha.xiaohashu.user.biz.domain.mapper.UserDOMapper;
import com.quanxiaoha.xiaohashu.user.biz.domain.mapper.UserRoleDOMapper;
import com.quanxiaoha.xiaohashu.user.biz.service.impl.UserServiceImpl;
import com.quanxiaoha.xiaohashu.user.dto.req.FindUserByPhoneReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.req.RegisterUserReqDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserRoleCacheTest {

    private UserDOMapper userDOMapper;
    private UserRoleDOMapper userRoleDOMapper;
    private ValueOperations<String, Object> valueOperations;
    private UserServiceImpl userService;

    /**
     * 创建仅包含角色缓存依赖的用户服务。
     */
    @BeforeEach
    void setUp() {
        userDOMapper = mock(UserDOMapper.class);
        userRoleDOMapper = mock(UserRoleDOMapper.class);
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "userDOMapper", userDOMapper);
        ReflectionTestUtils.setField(userService, "userRoleDOMapper", userRoleDOMapper);
        ReflectionTestUtils.setField(userService, "redisTemplate", redisTemplate);
    }

    /**
     * 密码登录查询用户时应重新写入该用户的有效角色。
     */
    @Test
    void shouldRefreshRolesWhenFindingUserByPhone() {
        UserDO user = UserDO.builder().id(10L).phone("13800000000").password("encoded").build();
        when(userDOMapper.selectByPhone("13800000000")).thenReturn(user);
        when(userRoleDOMapper.selectRoleKeysByUserId(10L))
                .thenReturn(List.of("common_user"));

        FindUserByPhoneReqDTO request = new FindUserByPhoneReqDTO();
        request.setPhone("13800000000");
        userService.findByPhone(request);

        verify(valueOperations).set(eq("user:roles:10"),
                argThat(value -> value.toString().contains("common_user")));
    }

    /**
     * 已注册用户再次通过验证码登录时也应恢复角色缓存。
     */
    @Test
    void shouldRefreshRolesWhenRegisteredUserLogsInAgain() {
        UserDO user = UserDO.builder().id(20L).phone("13900000000").build();
        when(userDOMapper.selectByPhone("13900000000")).thenReturn(user);
        when(userRoleDOMapper.selectRoleKeysByUserId(20L))
                .thenReturn(List.of("common_user"));

        RegisterUserReqDTO request = new RegisterUserReqDTO();
        request.setPhone("13900000000");
        userService.register(request);

        verify(valueOperations).set(eq("user:roles:20"),
                argThat(value -> value.toString().contains("common_user")));
    }
}
