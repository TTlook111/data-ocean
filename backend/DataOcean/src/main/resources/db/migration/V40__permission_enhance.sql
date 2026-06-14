-- V40: 权限增强 — 策略优先级 + 时间条件 + 权限变更审计

-- 1. 为 datasource_access_policy 表添加优先级和时间条件字段
ALTER TABLE datasource_access_policy
    ADD COLUMN priority INT NOT NULL DEFAULT 100 COMMENT '优先级（越低越优先：系统级 0-99，管理员 100-199，默认 200+）' AFTER row_filter_expression,
    ADD COLUMN valid_from DATETIME COMMENT '策略生效开始时间（NULL 表示立即生效）' AFTER priority,
    ADD COLUMN valid_until DATETIME COMMENT '策略生效结束时间（NULL 表示永久有效）' AFTER valid_from,
    ADD COLUMN time_schedule JSON COMMENT '时间计划：{"weekdays":[1,2,3,4,5],"hours":{"from":"09:00","to":"18:00"}}' AFTER valid_until;

-- 添加优先级索引（用于策略评估时的排序）
ALTER TABLE datasource_access_policy
    ADD INDEX idx_policy_priority (datasource_id, priority);

-- 2. 创建权限变更审计表
CREATE TABLE IF NOT EXISTS permission_change_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    change_type     VARCHAR(32) NOT NULL COMMENT '变更类型：CREATE/UPDATE/DELETE/GRANT/REVOKE',
    target_type     VARCHAR(16) NOT NULL COMMENT '目标类型：POLICY/ACCESS',
    target_id       BIGINT NOT NULL COMMENT '目标 ID',
    subject_type    VARCHAR(16) COMMENT '主体类型：USER/ROLE/DEPARTMENT',
    subject_id      BIGINT COMMENT '主体 ID',
    datasource_id   BIGINT COMMENT '数据源 ID',
    old_value       JSON COMMENT '变更前值',
    new_value       JSON COMMENT '变更后值',
    operator_id     BIGINT NOT NULL COMMENT '操作人 ID',
    reason          VARCHAR(512) COMMENT '变更原因',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_pcl_type (change_type),
    INDEX idx_pcl_time (created_at),
    INDEX idx_pcl_datasource (datasource_id),
    INDEX idx_pcl_subject (subject_type, subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限变更审计日志';
