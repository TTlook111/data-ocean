-- 元数据治理模块表结构
-- metadata_quality_rule, metadata_quality_issue, metadata_review_record

-- 质量规则表
CREATE TABLE metadata_quality_rule (
    id               BIGINT NOT NULL AUTO_INCREMENT,
    rule_code        VARCHAR(50) NOT NULL COMMENT '规则编码',
    rule_name        VARCHAR(200) NOT NULL COMMENT '规则名称',
    dimension        VARCHAR(20) NOT NULL COMMENT '校验维度: COMPLETENESS/ACCURACY/CONSISTENCY/TIMELINESS/TRACEABILITY',
    severity         VARCHAR(10) NOT NULL COMMENT '严重级别: HIGH/MEDIUM/LOW',
    description      VARCHAR(500) DEFAULT NULL COMMENT '规则描述',
    check_target     VARCHAR(20) NOT NULL COMMENT '校验目标: TABLE/COLUMN/RELATION',
    enabled          TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    deduction_points DECIMAL(4,1) NOT NULL COMMENT '扣分值',
    builtin          TINYINT NOT NULL DEFAULT 1 COMMENT '是否内置规则',
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_rule_code (rule_code),
    INDEX idx_dimension (dimension)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质量规则表';

-- 质量问题清单
CREATE TABLE metadata_quality_issue (
    id                BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_id       BIGINT NOT NULL COMMENT '关联快照',
    datasource_id     BIGINT NOT NULL COMMENT '数据源',
    rule_id           BIGINT NOT NULL COMMENT '触发的规则',
    dimension         VARCHAR(20) NOT NULL COMMENT '校验维度',
    severity          VARCHAR(10) NOT NULL COMMENT '严重级别',
    table_name        VARCHAR(200) NOT NULL COMMENT '涉及表',
    column_name       VARCHAR(200) DEFAULT NULL COMMENT '涉及字段（表级问题为NULL）',
    issue_description VARCHAR(1000) NOT NULL COMMENT '问题描述',
    suggestion        VARCHAR(1000) DEFAULT NULL COMMENT '修正建议',
    status            VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT '状态: OPEN/CONFIRMED/RESOLVED/REJECTED/AUTO_CLOSED',
    assignee_id       BIGINT DEFAULT NULL COMMENT '分派负责人',
    resolved_by       BIGINT DEFAULT NULL COMMENT '解决人',
    resolved_at       DATETIME DEFAULT NULL COMMENT '解决时间',
    resolution_note   VARCHAR(500) DEFAULT NULL COMMENT '解决备注',
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_snapshot_status (snapshot_id, status),
    INDEX idx_ds_dimension (datasource_id, dimension),
    INDEX idx_assignee (assignee_id, status),
    INDEX idx_severity (severity, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质量问题清单';

-- 治理审核记录
CREATE TABLE metadata_review_record (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    snapshot_id   BIGINT NOT NULL COMMENT '关联快照',
    datasource_id BIGINT NOT NULL COMMENT '数据源',
    target_type   VARCHAR(20) NOT NULL COMMENT '目标类型: TABLE/COLUMN',
    table_name    VARCHAR(200) NOT NULL COMMENT '表名',
    column_name   VARCHAR(200) DEFAULT NULL COMMENT '字段名',
    action        VARCHAR(30) NOT NULL COMMENT '操作: STATUS_CHANGE/ISSUE_CONFIRM/ISSUE_RESOLVE/ISSUE_REJECT/BATCH_STATUS_CHANGE/QUALITY_CHECK_TRIGGER',
    old_status    VARCHAR(20) DEFAULT NULL COMMENT '变更前状态',
    new_status    VARCHAR(20) DEFAULT NULL COMMENT '变更后状态',
    operator_id   BIGINT NOT NULL COMMENT '操作人',
    remark        VARCHAR(500) DEFAULT NULL COMMENT '备注',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_snapshot (snapshot_id),
    INDEX idx_ds_table (datasource_id, table_name),
    INDEX idx_operator (operator_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='治理审核记录';

-- 初始化内置质量规则
INSERT INTO metadata_quality_rule (rule_code, rule_name, dimension, severity, description, check_target, deduction_points) VALUES
-- 完整性规则
('COMP_TABLE_COMMENT_MISSING', '表注释缺失', 'COMPLETENESS', 'HIGH', '检测表是否缺少注释，无注释的表难以被业务人员理解', 'TABLE', 5.0),
('COMP_COLUMN_COMMENT_MISSING', '字段注释缺失', 'COMPLETENESS', 'MEDIUM', '检测字段是否缺少注释，无注释的字段影响SQL生成准确性', 'COLUMN', 2.0),
('COMP_PRIMARY_KEY_MISSING', '主键缺失', 'COMPLETENESS', 'HIGH', '检测表是否缺少主键定义', 'TABLE', 8.0),
-- 准确性规则
('ACCU_TYPE_NAME_MISMATCH', '字段类型与命名不匹配', 'ACCURACY', 'MEDIUM', '检测字段命名暗示的类型与实际类型不一致（如xxx_time不是时间类型）', 'COLUMN', 3.0),
('ACCU_ENUM_ANOMALY', '疑似枚举字段值异常', 'ACCURACY', 'LOW', '检测distinct_count过高的疑似枚举字段', 'COLUMN', 1.5),
-- 一致性规则
('CONS_CROSS_TABLE_TYPE_MISMATCH', '同名字段跨表类型不一致', 'CONSISTENCY', 'HIGH', '检测不同表中同名字段的数据类型是否一致', 'COLUMN', 5.0),
('CONS_CROSS_TABLE_COMMENT_CONFLICT', '同名字段注释冲突', 'CONSISTENCY', 'LOW', '检测不同表中同名字段的注释是否矛盾', 'COLUMN', 1.0),
-- 时效性规则
('TIME_SNAPSHOT_EXPIRED', '快照过期', 'TIMELINESS', 'MEDIUM', '检测快照距上次同步是否超过配置天数', 'TABLE', 3.0),
('TIME_TABLE_NO_UPDATE', '表长期无数据更新', 'TIMELINESS', 'LOW', '基于information_schema检测表是否长期无数据变更', 'TABLE', 2.0),
-- 可追溯性规则
('TRACE_FK_MISSING', '外键关系缺失', 'TRACEABILITY', 'MEDIUM', '检测有xxx_id命名但无外键定义的字段', 'COLUMN', 3.0),
('TRACE_ISOLATED_TABLE', '孤立表', 'TRACEABILITY', 'LOW', '检测无任何关联关系的表', 'TABLE', 2.0);
