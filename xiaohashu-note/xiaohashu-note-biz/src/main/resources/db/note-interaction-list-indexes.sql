-- 在笔记库手动执行；先核对重复关系，不自动删除或合并业务数据。
-- 已有等价索引时跳过；若存在重复关系，新增唯一索引会失败，需先人工核对。

SELECT user_id, note_id, COUNT(*) AS duplicate_count FROM t_note_like
GROUP BY user_id, note_id HAVING COUNT(*) > 1;
SET @has_unique_relation = (
    SELECT COUNT(*) FROM (
        SELECT INDEX_NAME FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_note_like' AND NON_UNIQUE = 0
        GROUP BY INDEX_NAME
        HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) IN ('user_id,note_id', 'note_id,user_id')
    ) existing_unique_indexes
);
SET @index_ddl = IF(@has_unique_relation > 0, 'SELECT 1',
    'ALTER TABLE t_note_like ADD UNIQUE KEY uk_user_note (user_id, note_id)');
PREPARE interaction_index_statement FROM @index_ddl;
EXECUTE interaction_index_statement;
DEALLOCATE PREPARE interaction_index_statement;

-- 同方向倒序可反向扫描升序索引，ID 作为相同互动时间的稳定排序条件。
SET @has_list_index = (
    SELECT COUNT(*) FROM (
        SELECT INDEX_NAME FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_note_like'
        GROUP BY INDEX_NAME
        HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'user_id,status,create_time,id'
           AND MIN(COALESCE(COLLATION, 'A')) = MAX(COALESCE(COLLATION, 'A'))
    ) existing_list_indexes
);
SET @index_ddl = IF(@has_list_index > 0, 'SELECT 1',
    'ALTER TABLE t_note_like ADD INDEX idx_interaction_user_status_time_id (user_id, status, create_time, id)');
PREPARE interaction_index_statement FROM @index_ddl;
EXECUTE interaction_index_statement;
DEALLOCATE PREPARE interaction_index_statement;


SELECT user_id, note_id, COUNT(*) AS duplicate_count FROM t_note_collection
GROUP BY user_id, note_id HAVING COUNT(*) > 1;
SET @has_unique_relation = (
    SELECT COUNT(*) FROM (
        SELECT INDEX_NAME FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_note_collection' AND NON_UNIQUE = 0
        GROUP BY INDEX_NAME
        HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) IN ('user_id,note_id', 'note_id,user_id')
    ) existing_unique_indexes
);
SET @index_ddl = IF(@has_unique_relation > 0, 'SELECT 1',
    'ALTER TABLE t_note_collection ADD UNIQUE KEY uk_user_note (user_id, note_id)');
PREPARE interaction_index_statement FROM @index_ddl;
EXECUTE interaction_index_statement;
DEALLOCATE PREPARE interaction_index_statement;

-- 同方向倒序可反向扫描升序索引，ID 作为相同互动时间的稳定排序条件。
SET @has_list_index = (
    SELECT COUNT(*) FROM (
        SELECT INDEX_NAME FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_note_collection'
        GROUP BY INDEX_NAME
        HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'user_id,status,create_time,id'
           AND MIN(COALESCE(COLLATION, 'A')) = MAX(COALESCE(COLLATION, 'A'))
    ) existing_list_indexes
);
SET @index_ddl = IF(@has_list_index > 0, 'SELECT 1',
    'ALTER TABLE t_note_collection ADD INDEX idx_interaction_user_status_time_id (user_id, status, create_time, id)');
PREPARE interaction_index_statement FROM @index_ddl;
EXECUTE interaction_index_statement;
DEALLOCATE PREPARE interaction_index_statement;
