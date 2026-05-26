-- ============================================================
-- 字段 Tag 与可信度模块 - 建表脚本
-- 包含：field_tag、field_confidence、field_confidence_event、
--       user_feedback、feedback_review 五张表
-- ============================================================

-- 字段标签表：记录字段的业务标签
CREATE TABLE IF NOT EXISTS field_tag (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    column_meta_id  BIGINT       NOT NULL COMMENT '关联的字段元数据ID',
    tag_code        VARCHAR(50)  NOT NULL COMMENT '标签编码（如 AMOUNT、TIME、STATUS）',
    tag_name        VARCHAR(100) NOT NULL COMMENT '标签显示名称',
    source          VARCHAR(20)  NOT NULL DEFAULT 'MANUAL' COMMENT '标签来源：SYSTEM/MANUAL/AI_SUGGESTED',
    created_by      BIGINT       NULL COMMENT '创建人用户ID',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_field_tag_column (column_meta_id),
    INDEX idx_field_tag_code (tag_code),
    UNIQUE INDEX uk_column_tag (column_meta_id, tag_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字段标签表';

-- 字段可信度表：记录字段的可信度评分
CREATE TABLE IF NOT EXISTS field_confidence (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    column_meta_id  BIGINT      NOT NULL COMMENT '关联的字段元数据ID',
    score           INT         NOT NULL DEFAULT 30 COMMENT '可信度分数（0-100）',
    level           VARCHAR(10) NOT NULL DEFAULT 'LOW' COMMENT '可信度等级：HIGH(>=70)/MEDIUM(>=40)/LOW(<40)',
    reason          VARCHAR(500) NULL COMMENT '当前分数的原因说明',
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    updated_by      BIGINT      NULL COMMENT '最后更新人用户ID',
    UNIQUE INDEX uk_confidence_column (column_meta_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字段可信度表';

-- 字段可信度变更事件表：记录每次可信度变更的流水
CREATE TABLE IF NOT EXISTS field_confidence_event (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    column_meta_id  BIGINT      NOT NULL COMMENT '关联的字段元数据ID',
    delta_score     INT         NOT NULL COMMENT '分数变化量（正数加分，负数扣分）',
    event_type      VARCHAR(30) NOT NULL COMMENT '事件类型：SCHEMA_INIT/SKILLS_MD_DEFINED/MANUAL_CONFIRM/ADMIN_SET/QUERY_SUCCESS/USER_LIKE/USER_DISLIKE_CONFIRMED/GROUP_THRESHOLD',
    source_query_id BIGINT      NULL COMMENT '关联的查询任务ID（如有）',
    operator_id     BIGINT      NULL COMMENT '操作人用户ID',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_event_column (column_meta_id),
    INDEX idx_event_type (event_type),
    INDEX idx_event_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字段可信度变更事件表';

-- 用户反馈表：记录用户对查询结果的反馈
CREATE TABLE IF NOT EXISTS user_feedback (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    query_task_id   BIGINT      NOT NULL COMMENT '关联的查询任务ID',
    column_meta_id  BIGINT      NOT NULL COMMENT '关联的字段元数据ID',
    user_id         BIGINT      NOT NULL COMMENT '反馈用户ID',
    feedback_type   VARCHAR(10) NOT NULL COMMENT '反馈类型：LIKE/DISLIKE',
    reason_code     VARCHAR(50) NULL COMMENT '原因编码（如 DATA_INACCURATE、FIELD_WRONG）',
    comment         VARCHAR(500) NULL COMMENT '用户补充说明',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_feedback_column (column_meta_id),
    INDEX idx_feedback_user (user_id),
    INDEX idx_feedback_task (query_task_id),
    INDEX idx_feedback_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户反馈表';

-- 反馈审核表：记录管理员对负向反馈的审核结果
CREATE TABLE IF NOT EXISTS feedback_review (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    feedback_id     BIGINT      NOT NULL COMMENT '关联的用户反馈ID',
    review_status   VARCHAR(10) NOT NULL DEFAULT 'PENDING' COMMENT '审核状态：PENDING/APPROVED/REJECTED',
    reviewer_id     BIGINT      NULL COMMENT '审核人用户ID',
    review_comment  VARCHAR(500) NULL COMMENT '审核意见',
    handled_at      DATETIME    NULL COMMENT '审核处理时间',
    INDEX idx_review_feedback (feedback_id),
    INDEX idx_review_status (review_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='反馈审核表';
