-- 停止旧版笔记消费者后，在笔记服务数据库执行，再部署新版消费者和补偿任务。
-- 前置：已执行 count-interaction.sql。
-- 可重复执行。历史非零增量重新进入待处理，由原事件身份及 Redis seen 去重；不得清空 seen。
-- 本脚本只增加必要的任务状态和异常记录，不部署可选的列表性能索引。

SET @has_column = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_note_interaction_result' AND COLUMN_NAME = 'count_status');
SET @task_ddl = IF(@has_column > 0, 'SELECT 1',
    'ALTER TABLE t_note_interaction_result ADD COLUMN count_status TINYINT NOT NULL DEFAULT 0');
PREPARE interaction_task_statement FROM @task_ddl;
EXECUTE interaction_task_statement;
DEALLOCATE PREPARE interaction_task_statement;

SET @has_column = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_note_interaction_result' AND COLUMN_NAME = 'count_retry_count');
SET @task_ddl = IF(@has_column > 0, 'SELECT 1',
    'ALTER TABLE t_note_interaction_result ADD COLUMN count_retry_count INT NOT NULL DEFAULT 0');
PREPARE interaction_task_statement FROM @task_ddl;
EXECUTE interaction_task_statement;
DEALLOCATE PREPARE interaction_task_statement;

SET @has_column = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_note_interaction_result' AND COLUMN_NAME = 'count_next_retry_time');
SET @task_ddl = IF(@has_column > 0, 'SELECT 1',
    'ALTER TABLE t_note_interaction_result ADD COLUMN count_next_retry_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)');
PREPARE interaction_task_statement FROM @task_ddl;
EXECUTE interaction_task_statement;
DEALLOCATE PREPARE interaction_task_statement;

SET @has_column = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_note_interaction_result' AND COLUMN_NAME = 'count_last_error');
SET @task_ddl = IF(@has_column > 0, 'SELECT 1',
    'ALTER TABLE t_note_interaction_result ADD COLUMN count_last_error VARCHAR(64) NULL');
PREPARE interaction_task_statement FROM @task_ddl;
EXECUTE interaction_task_statement;
DEALLOCATE PREPARE interaction_task_statement;

SET @has_index = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_note_interaction_result' AND INDEX_NAME = 'idx_count_pending');
SET @task_ddl = IF(@has_index > 0, 'SELECT 1',
    'ALTER TABLE t_note_interaction_result ADD INDEX idx_count_pending (count_status, count_next_retry_time, user_id, request_id)');
PREPARE interaction_task_statement FROM @task_ddl;
EXECUTE interaction_task_statement;
DEALLOCATE PREPARE interaction_task_statement;
UPDATE t_note_interaction_result SET count_status = 2 WHERE actual_delta = 0 AND count_status = 0;

CREATE TABLE IF NOT EXISTS t_note_interaction_failure (
    message_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic VARCHAR(255) NULL,
    broker_message_id VARCHAR(128) NULL,
    queue_id INT NOT NULL,
    queue_offset BIGINT NOT NULL,
    body LONGBLOB NULL,
    reason_code VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (message_key)
) ENGINE=InnoDB;
