-- 流水只存问题身份、范围与状态，不复制问题正文。自动重检的批量 SQL 和人工操作共用同一事务记录。
CREATE TABLE quality_issue_transition (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    issue_id BIGINT NOT NULL,
    datasource_id BIGINT NOT NULL,
    snapshot_id BIGINT NOT NULL,
    previous_status VARCHAR(20) NULL,
    next_status VARCHAR(20) NULL,
    occurred_at DATETIME(6) NOT NULL COMMENT '状态写入时刻，上海业务时间',
    INDEX idx_issue_trend_scope_time (datasource_id, snapshot_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 独立标记全部写路径开始受观察的时刻；旧库只有当前状态，早于此刻的事件数未知。
CREATE TABLE quality_issue_trend_coverage (
    id TINYINT NOT NULL PRIMARY KEY,
    started_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;

CREATE TRIGGER trg_issue_history_insert AFTER INSERT ON metadata_quality_issue
FOR EACH ROW INSERT INTO quality_issue_transition
    (issue_id, datasource_id, snapshot_id, previous_status, next_status, occurred_at)
VALUES (NEW.id, NEW.datasource_id, NEW.snapshot_id, NULL, NEW.status, UTC_TIMESTAMP(6) + INTERVAL 8 HOUR);

-- 普通分派与文案改动不生成流量；若问题归属发生转移，两个范围分别记录移出和移入。
DELIMITER $$
CREATE TRIGGER trg_issue_history_update AFTER UPDATE ON metadata_quality_issue
FOR EACH ROW
BEGIN
    IF OLD.datasource_id <> NEW.datasource_id OR OLD.snapshot_id <> NEW.snapshot_id THEN
        INSERT INTO quality_issue_transition
            (issue_id, datasource_id, snapshot_id, previous_status, next_status, occurred_at)
        VALUES
            (OLD.id, OLD.datasource_id, OLD.snapshot_id, OLD.status, NULL, UTC_TIMESTAMP(6) + INTERVAL 8 HOUR),
            (NEW.id, NEW.datasource_id, NEW.snapshot_id, NULL, NEW.status, UTC_TIMESTAMP(6) + INTERVAL 8 HOUR);
    ELSEIF OLD.status <> NEW.status THEN
        INSERT INTO quality_issue_transition
            (issue_id, datasource_id, snapshot_id, previous_status, next_status, occurred_at)
        VALUES (NEW.id, NEW.datasource_id, NEW.snapshot_id, OLD.status, NEW.status, UTC_TIMESTAMP(6) + INTERVAL 8 HOUR);
    END IF;
END$$
DELIMITER ;

-- 删除未解决问题也会改变期末余量，单列在其他移出，不能增加人工解决数。
CREATE TRIGGER trg_issue_history_delete AFTER DELETE ON metadata_quality_issue
FOR EACH ROW INSERT INTO quality_issue_transition
    (issue_id, datasource_id, snapshot_id, previous_status, next_status, occurred_at)
VALUES (OLD.id, OLD.datasource_id, OLD.snapshot_id, OLD.status, NULL, UTC_TIMESTAMP(6) + INTERVAL 8 HOUR);

INSERT INTO quality_issue_trend_coverage (id, started_at) VALUES (1, UTC_TIMESTAMP(6) + INTERVAL 8 HOUR);
