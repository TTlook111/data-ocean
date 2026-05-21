-- 快照操作审计日志表
CREATE TABLE snapshot_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    snapshot_id BIGINT NOT NULL COMMENT '关联快照ID',
    datasource_id BIGINT NOT NULL COMMENT '数据源ID',
    action VARCHAR(30) NOT NULL COMMENT '操作类型：STATUS_TRANSITION/PUBLISH/EXPIRE/REVOKE/QUALITY_CHECK_START/QUALITY_CHECK_COMPLETE/AUTO_CLEANUP',
    old_status VARCHAR(20) DEFAULT NULL COMMENT '变更前状态',
    new_status VARCHAR(20) DEFAULT NULL COMMENT '变更后状态',
    operator_id BIGINT NOT NULL COMMENT '操作人ID',
    reason VARCHAR(500) DEFAULT NULL COMMENT '操作原因/备注',
    context_json JSON DEFAULT NULL COMMENT '操作上下文（附加信息）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (id),
    INDEX idx_snapshot (snapshot_id, created_at DESC),
    INDEX idx_datasource (datasource_id, created_at DESC),
    INDEX idx_operator (operator_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='快照操作审计日志';
