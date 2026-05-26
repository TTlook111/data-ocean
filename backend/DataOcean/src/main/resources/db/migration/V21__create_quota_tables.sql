-- ============================================================
-- 血缘与审计模块 - 配额策略表 + 告警规则表
-- ============================================================

-- 查询配额策略表
CREATE TABLE IF NOT EXISTS quota_policy (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    subject_type        VARCHAR(20)  NOT NULL COMMENT '主体类型：USER/DEPARTMENT/DATASOURCE',
    subject_id          BIGINT       NOT NULL COMMENT '主体ID（用户ID/部门ID/数据源ID）',
    daily_query_limit   INT          NULL COMMENT '每日查询次数上限',
    monthly_cost_limit  DECIMAL(10,2) NULL COMMENT '月度成本上限（元）',
    enabled             TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE INDEX uk_quota_subject (subject_type, subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='查询配额策略表';

-- 告警规则表
CREATE TABLE IF NOT EXISTS alert_rule (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    metric              VARCHAR(50)  NOT NULL COMMENT '监控指标（ERROR_RATE/SLOW_QUERY_COUNT/COST_DAILY）',
    threshold           DECIMAL(10,2) NOT NULL COMMENT '阈值',
    operator            VARCHAR(5)   NOT NULL DEFAULT '>' COMMENT '比较运算符：>/</>=/<=/=',
    notification_type   VARCHAR(20)  NOT NULL DEFAULT 'SYSTEM' COMMENT '通知方式：SYSTEM/EMAIL',
    enabled             TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警规则表';
