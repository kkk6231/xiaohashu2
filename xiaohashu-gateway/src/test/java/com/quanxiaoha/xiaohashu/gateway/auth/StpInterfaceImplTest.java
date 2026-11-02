package com.quanxiaoha.xiaohashu.gateway.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StpInterfaceImplTest {

    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private StpInterfaceImpl stpInterface;

    /**
     * 创建不依赖 Spring 容器的权限读取服务。
     */
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        stpInterface = new StpInterfaceImpl();
        ReflectionTestUtils.setField(stpInterface, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(stpInterface, "objectMapper", new ObjectMapper());
    }

    /**
     * 用户角色缓存不存在时应返回空权限集合。
     */
    @Test
    void shouldReturnEmptyPermissionsWhenUserHasNoRoleCache() {
        when(valueOperations.get("user:roles:1")).thenReturn(null);

        List<String> permissions = stpInterface.getPermissionList(1L, "login");

        assertTrue(permissions.isEmpty());
    }

    /**
     * 多个角色的权限应合并去重，并忽略缺失的角色权限缓存。
     */
    @Test
    void shouldMergeAndDeduplicateRolePermissions() {
        when(valueOperations.get("user:roles:1"))
                .thenReturn("[\"common_user\",\"creator\",\"missing\"]");
        when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(
                "[\"app:note:publish\",\"app:file:upload\"]",
                "[\"app:note:publish\",\"app:comment:publish\"]",
                null));

        List<String> permissions = stpInterface.getPermissionList(1L, "login");

        assertEquals(List.of(
                "app:note:publish",
                "app:file:upload",
                "app:comment:publish"), permissions);
    }

    /**
     * 单个角色缓存损坏时应跳过该角色，并保留其他角色的有效权限。
     */
    @Test
    void shouldIgnoreMalformedRolePermissionCache() {
        when(valueOperations.get("user:roles:1"))
                .thenReturn("[\"broken\",\"common_user\"]");
        when(valueOperations.multiGet(anyList())).thenReturn(List.of(
                "not-json",
                "[\"app:note:publish\"]"));

        List<String> permissions = stpInterface.getPermissionList(1L, "login");

        assertEquals(List.of("app:note:publish"), permissions);
    }
}
