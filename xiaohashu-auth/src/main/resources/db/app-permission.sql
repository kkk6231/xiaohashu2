-- APP 端发布类权限初始化脚本，可重复执行。
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

-- 项目中的普通用户角色 ID 固定为 1。
INSERT INTO t_role_permission_rel
    (role_id, permission_id, create_time, update_time, is_deleted)
SELECT 1, permission.id, NOW(), NOW(), 0
FROM t_permission permission
WHERE permission.permission_key IN (
        'app:note:publish',
        'app:comment:publish',
        'app:file:upload'
    )
  AND permission.is_deleted = 0
  AND NOT EXISTS (
      SELECT 1
      FROM t_role_permission_rel role_permission
      WHERE role_permission.role_id = 1
        AND role_permission.permission_id = permission.id
        AND role_permission.is_deleted = 0
  );
