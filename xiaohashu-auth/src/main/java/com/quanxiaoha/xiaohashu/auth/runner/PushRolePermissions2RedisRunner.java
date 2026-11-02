package com.quanxiaoha.xiaohashu.auth.runner;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.auth.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.auth.domain.dataobject.PermissionDO;
import com.quanxiaoha.xiaohashu.auth.domain.dataobject.RoleDO;
import com.quanxiaoha.xiaohashu.auth.domain.dataobject.RolePermissionDO;
import com.quanxiaoha.xiaohashu.auth.domain.mapper.PermissionDOMapper;
import com.quanxiaoha.xiaohashu.auth.domain.mapper.RoleDOMapper;
import com.quanxiaoha.xiaohashu.auth.domain.mapper.RolePermissionDOMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Auth 服务启动时将有效角色及其权限同步到 Redis。
 */
@Component
@Slf4j
public class PushRolePermissions2RedisRunner implements ApplicationRunner {

    @Resource
    private RedisTemplate<String, String> redisTemplate;
    @Resource
    private RoleDOMapper roleDOMapper;
    @Resource
    private PermissionDOMapper permissionDOMapper;
    @Resource
    private RolePermissionDOMapper rolePermissionDOMapper;

    /**
     * 服务启动时重建有效角色的权限缓存。
     *
     * @param args 应用启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("==> 服务启动，开始同步角色权限数据到 Redis 中...");
        try {
            List<RoleDO> roles = roleDOMapper.selectEnabledList();
            if (roles == null || roles.isEmpty()) {
                log.warn("==> 未查询到有效角色，跳过角色权限同步");
                return;
            }

            Map<Long, PermissionDO> permissionMap = loadPermissionMap();
            Map<Long, List<Long>> rolePermissionIds = loadRolePermissionIds(roles);
            writeRolePermissions(roles, rolePermissionIds, permissionMap);
            log.info("==> 服务启动，成功同步角色权限数据到 Redis 中...");
        } catch (Exception exception) {
            log.error("==> 同步角色权限数据到 Redis 中失败: ", exception);
        }
    }

    /**
     * 查询全部有效 APP 权限并按权限 ID 建立索引。
     *
     * @return 权限 ID 与权限信息的映射
     */
    private Map<Long, PermissionDO> loadPermissionMap() {
        List<PermissionDO> permissions = permissionDOMapper.selectAppEnabledList();
        Map<Long, PermissionDO> permissionMap = new HashMap<>();
        if (permissions == null) {
            return permissionMap;
        }

        for (PermissionDO permission : permissions) {
            permissionMap.put(permission.getId(), permission);
        }
        return permissionMap;
    }

    /**
     * 查询角色与权限 ID 的关系并按角色 ID 分组。
     *
     * @param roles 有效角色集合
     * @return 角色 ID 与权限 ID 集合的映射
     */
    private Map<Long, List<Long>> loadRolePermissionIds(List<RoleDO> roles) {
        List<Long> roleIds = new ArrayList<>(roles.size());
        for (RoleDO role : roles) {
            roleIds.add(role.getId());
        }

        List<RolePermissionDO> relations = rolePermissionDOMapper.selectByRoleIds(roleIds);
        Map<Long, List<Long>> rolePermissionIds = new HashMap<>();
        if (relations == null) {
            return rolePermissionIds;
        }

        for (RolePermissionDO relation : relations) {
            List<Long> permissionIds = rolePermissionIds.get(relation.getRoleId());
            if (permissionIds == null) {
                permissionIds = new ArrayList<>();
                rolePermissionIds.put(relation.getRoleId(), permissionIds);
            }
            permissionIds.add(relation.getPermissionId());
        }
        return rolePermissionIds;
    }

    /**
     * 将每个有效角色的权限标识写入 Redis；没有权限的角色写入空集合。
     *
     * @param roles 有效角色集合
     * @param rolePermissionIds 角色与权限 ID 的映射
     * @param permissionMap 有效权限索引
     */
    private void writeRolePermissions(List<RoleDO> roles,
                                      Map<Long, List<Long>> rolePermissionIds,
                                      Map<Long, PermissionDO> permissionMap) {
        for (RoleDO role : roles) {
            List<Long> permissionIds = rolePermissionIds.getOrDefault(
                    role.getId(), Collections.emptyList());
            List<String> permissionKeys = new ArrayList<>();
            for (Long permissionId : permissionIds) {
                PermissionDO permission = permissionMap.get(permissionId);
                if (permission != null) {
                    permissionKeys.add(permission.getPermissionKey());
                }
            }

            String redisKey = RedisKeyConstants.buildRolePermissionsKey(role.getRoleKey());
            redisTemplate.opsForValue().set(redisKey, JsonUtils.toJsonString(permissionKeys));
        }
    }
}
