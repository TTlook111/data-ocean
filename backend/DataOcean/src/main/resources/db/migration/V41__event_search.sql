-- V41: 阶段七 事件驱动 — 元数据变更事件 + 数据访问审批

-- 1. 元数据变更事件表
-- 记录元数据实体的变更历史，支持事件传播和审计追溯。
CREATE TABLE IF NOT EXISTS metadata_change_event (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_type      VARCHAR(32) NOT NULL COMMENT '事件类型：CREATE/UPDATE/DELETE/PUBLISH',
    entity_type     VARCHAR(32) NOT NULL COMMENT '实体类型：TABLE/COLUMN/GLOSSARY_TERM/TAG',
    entity_id       BIGINT NOT NULL COMMENT '实体 ID',
    entity_fqn      VARCHAR(512) NOT NULL COMMENT '实体全限定名',
    change_data     JSON COMMENT '变更数据（包含变更前后的字段值）',
    operator_id     BIGINT COMMENT '操作人 ID',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_mce_type (event_type),
    INDEX idx_mce_entity (entity_type, entity_id),
    INDEX idx_mce_time (created_at),
    INDEX idx_mce_fqn (entity_fqn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='元数据变更事件表';

-- 2. 数据访问审批请求表
-- 支持用户因 MASK 结果申请查看，管理员审批后生成临时 ALLOW 策略。
CREATE TABLE IF NOT EXISTS access_approval_request (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    requester_id        BIGINT NOT NULL COMMENT '申请人 ID',
    datasource_id       BIGINT NOT NULL COMMENT '数据源 ID',
    table_name          VARCHAR(128) NOT NULL COMMENT '表名',
    column_name         VARCHAR(128) COMMENT '列名（NULL 表示表级申请）',
    request_reason      TEXT NOT NULL COMMENT '申请理由',
    requested_duration  INT COMMENT '申请时长（小时）',
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/APPROVED/REJECTED/EXPIRED',
    approver_id         BIGINT COMMENT '审批人 ID',
    approved_at         DATETIME COMMENT '审批时间',
    expires_at          DATETIME COMMENT '临时策略过期时间',
    reject_reason       TEXT COMMENT '拒绝理由',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_aar_status (status),
    INDEX idx_aar_requester (requester_id),
    INDEX idx_aar_datasource (datasource_id),
    INDEX idx_aar_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据访问审批请求表';
