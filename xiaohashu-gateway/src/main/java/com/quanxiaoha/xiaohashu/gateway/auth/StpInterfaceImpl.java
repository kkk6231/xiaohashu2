package com.quanxiaoha.xiaohashu.gateway.auth;

import cn.dev33.satoken.stp.StpInterface;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.xiaohashu.gateway.constant.RedisKeyConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从 Redis 中读取用户角色及角色权限，供 Sa-Token 完成 RBAC 校验。
 */
@Component
@Slf4j
public class StpInterfaceImpl implements StpInterface {

    @Resource
    private RedisTemplate<String, String> redisTemplate;
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 查询登录用户拥有的全部权限。
     *
     * @param loginId 登录用户 ID
     * @param loginType 登录类型
     * @return 去重后的权限标识集合
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long userId = Long.valueOf(loginId.toString());
        List<String> roleKeys = readUserRoles(userId);
        if (roleKeys.isEmpty()) {
            return Collections.emptyList();
        }
        return readRolePermissions(roleKeys);
    }

    /**
     * 查询登录用户拥有的角色。
     *
     * @param loginId 登录用户 ID
     * @param loginType 登录类型
     * @return 用户角色标识集合
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = Long.valueOf(loginId.toString());
        return readUserRoles(userId);
    }

    /**
     * 从 Redis 中读取指定用户的角色标识。
     *
     * @param userId 用户 ID
     * @return 用户角色标识集合
     */
    private List<String> readUserRoles(Long userId) {
        log.info("## 获取用户角色列表, userId: {}", userId);
        String userRolesKey = RedisKeyConstants.buildUserRoleKey(userId);
        String userRolesValue = redisTemplate.opsForValue().get(userRolesKey);
        if (StringUtils.isBlank(userRolesValue)) {
            return Collections.emptyList();
        }
        return parseStringList(userRolesValue, userRolesKey);
    }

    /**
     * 批量读取多个角色的权限并去重。
     *
     * @param roleKeys 角色标识集合
     * @return 去重后的权限标识集合
     */
    private List<String> readRolePermissions(List<String> roleKeys) {
        List<String> permissionRedisKeys = new ArrayList<>(roleKeys.size());
        for (String roleKey : roleKeys) {
            if (StringUtils.isNotBlank(roleKey)) {
                permissionRedisKeys.add(RedisKeyConstants.buildRolePermissionsKey(roleKey));
            }
        }
        if (permissionRedisKeys.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> permissionValues = redisTemplate.opsForValue().multiGet(permissionRedisKeys);
        if (permissionValues == null || permissionValues.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> permissionSet = new LinkedHashSet<>();
        for (int index = 0; index < permissionValues.size(); index++) {
            String permissionValue = permissionValues.get(index);
            if (StringUtils.isBlank(permissionValue)) {
                continue;
            }

            String redisKey = permissionRedisKeys.get(index);
            List<String> rolePermissions = parseStringList(permissionValue, redisKey);
            permissionSet.addAll(rolePermissions);
        }
        return new ArrayList<>(permissionSet);
    }

    /**
     * 将 Redis 中保存的 JSON 数组解析成字符串集合。
     *
     * @param jsonValue JSON 数组
     * @param redisKey 数据来源 Redis Key
     * @return 解析成功的字符串集合，解析失败时返回空集合
     */
    private List<String> parseStringList(String jsonValue, String redisKey) {
        try {
            List<String> values = objectMapper.readValue(jsonValue, new TypeReference<List<String>>() {
            });
            if (values == null) {
                return Collections.emptyList();
            }
            return values;
        } catch (JsonProcessingException exception) {
            log.error("==> 角色权限缓存解析失败, redisKey: {}", redisKey, exception);
            return Collections.emptyList();
        }
    }
}
