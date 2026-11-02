-- APP 端角色和功能权限初始化脚本，可重复执行。
INSERT INTO t_role
    (role_name, role_key, `status`, sort, remark, create_time, update_time, is_deleted)
SELECT '受限用户', 'restricted_user', 0, 2, '不允许新增发布、互动和关注行为',
       NOW(), NOW(), 0
WHERE NOT EXISTS (
    SELECT 1 FROM t_role WHERE role_key = 'restricted_user' AND is_deleted = 0
);

INSERT INTO t_role
    (role_name, role_key, `status`, sort, remark, create_time, update_time, is_deleted)
SELECT '平台管理员', 'platform_admin', 0, 3, '负责用户访问状态管理',
       NOW(), NOW(), 0
WHERE NOT EXISTS (
    SELECT 1 FROM t_role WHERE role_key = 'platform_admin' AND is_deleted = 0
);

INSERT INTO t_permission
    (parent_id, `name`, `type`, menu_url, menu_icon, sort, permission_key,
     `status`, create_time, update_time, is_deleted)
SELECT 0, '发布笔记', 3, NULL, NULL, 1, 'app:note:publish',
       0, NOW(), NOW(), 0
WHERE NOT EXISTS (
    SELECT 1 FROM t_permission WHERE permission_key = 'app:note:publish' AND is_deleted = 0
);

INSERT INTO t_permission
    (parent_id, `name`, `type`, menu_url, menu_icon, sort, permission_key,
     `status`, create_time, update_time, is_deleted)
SELECT 0, '发布评论', 3, NULL, NULL, 2, 'app:comment:publish',
       0, NOW(), NOW(), 0
WHERE NOT EXISTS (
    SELECT 1 FROM t_permission WHERE permission_key = 'app:comment:publish' AND is_deleted = 0
);

INSERT INTO t_permission
    (parent_id, `name`, `type`, menu_url, menu_icon, sort, permission_key,
     `status`, create_time, update_time, is_deleted)
SELECT 0, '上传文件', 3, NULL, NULL, 3, 'app:file:upload',
       0, NOW(), NOW(), 0
WHERE NOT EXISTS (
    SELECT 1 FROM t_permission WHERE permission_key = 'app:file:upload' AND is_deleted = 0
);

INSERT INTO t_permission
    (parent_id, `name`, `type`, menu_url, menu_icon, sort, permission_key,
     `status`, create_time, update_time, is_deleted)
SELECT 0, '创建社交互动', 3, NULL, NULL, 4, 'app:social:interact',
       0, NOW(), NOW(), 0
WHERE NOT EXISTS (
    SELECT 1 FROM t_permission WHERE permission_key = 'app:social:interact' AND is_deleted = 0
);

INSERT INTO t_permission
    (parent_id, `name`, `type`, menu_url, menu_icon, sort, permission_key,
     `status`, create_time, update_time, is_deleted)
SELECT 0, '创建关注关系', 3, NULL, NULL, 5, 'app:relation:write',
       0, NOW(), NOW(), 0
WHERE NOT EXISTS (
    SELECT 1 FROM t_permission WHERE permission_key = 'app:relation:write' AND is_deleted = 0
);

INSERT INTO t_permission
    (parent_id, `name`, `type`, menu_url, menu_icon, sort, permission_key,
     `status`, create_time, update_time, is_deleted)
SELECT 0, '管理用户访问状态', 3, NULL, NULL, 6, 'app:user:manage',
       0, NOW(), NOW(), 0
WHERE NOT EXISTS (
    SELECT 1 FROM t_permission WHERE permission_key = 'app:user:manage' AND is_deleted = 0
);

-- 项目中的普通用户角色 ID 固定为 1。
INSERT INTO t_role_permission_rel
    (role_id, permission_id, create_time, update_time, is_deleted)
SELECT 1, permission.id, NOW(), NOW(), 0
FROM t_permission permission
WHERE permission.permission_key IN (
        'app:note:publish',
        'app:comment:publish',
        'app:file:upload',
        'app:social:interact',
        'app:relation:write'
    )
  AND permission.is_deleted = 0
  AND NOT EXISTS (
      SELECT 1
      FROM t_role_permission_rel role_permission
      WHERE role_permission.role_id = 1
        AND role_permission.permission_id = permission.id
        AND role_permission.is_deleted = 0
  );

-- 平台管理员只获得用户管理权限；需要使用 APP 时可额外绑定普通用户角色。
INSERT INTO t_role_permission_rel
    (role_id, permission_id, create_time, update_time, is_deleted)
SELECT role.id, permission.id, NOW(), NOW(), 0
FROM t_role role
inner join t_permission permission on permission.permission_key = 'app:user:manage'
WHERE role.role_key = 'platform_admin'
  AND role.is_deleted = 0
  AND permission.is_deleted = 0
  AND NOT EXISTS (
      SELECT 1
      FROM t_role_permission_rel role_permission
      WHERE role_permission.role_id = role.id
        AND role_permission.permission_id = permission.id
        AND role_permission.is_deleted = 0
  );

-- 首个管理员账号由项目初始化时绑定 platform_admin 角色，不在通用脚本中硬编码用户 ID。
