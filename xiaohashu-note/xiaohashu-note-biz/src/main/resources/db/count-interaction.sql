-- 在笔记服务数据库执行。现有互动表须保留 (user_id, note_id) 唯一索引。
CREATE TABLE IF NOT EXISTS t_note_event_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    note_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload JSON NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_until DATETIME(3) NULL,
    claim_token VARCHAR(36) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sent_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_retry (status, next_retry_time, id),
    KEY idx_lease (status, lease_until, id)
) ENGINE=InnoDB;
