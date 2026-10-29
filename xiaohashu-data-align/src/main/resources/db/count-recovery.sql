-- 停止旧版关系、笔记、计数服务后执行。当前四个服务必须连接同一个事实库。
-- 完成后统一部署新版，不允许旧版服务绕过恢复屏障继续写入。
CREATE TABLE IF NOT EXISTS t_count_generation (
 id INT PRIMARY KEY, generation BIGINT NOT NULL, status VARCHAR(16) NOT NULL,
 rebuild_id VARCHAR(64) NULL, next_generation BIGINT NULL
) ENGINE=InnoDB;
INSERT IGNORE INTO t_count_generation(id,generation,status) VALUES(1,1,'READY');
CREATE TABLE IF NOT EXISTS t_count_rebuild (
 rebuild_id VARCHAR(64) PRIMARY KEY, previous_generation BIGINT NOT NULL,
 next_generation BIGINT NOT NULL, status VARCHAR(16) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), completed_at DATETIME(3) NULL
) ENGINE=InnoDB;
-- 一次性迁移；历史互动结果归属第一代，重试不能改为当前代次。
ALTER TABLE t_note_interaction_result ADD COLUMN count_generation BIGINT NOT NULL DEFAULT 1;
