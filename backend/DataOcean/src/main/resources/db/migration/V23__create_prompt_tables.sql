-- ============================================================
-- Prompt 管理模块 - 模板表和版本表
-- ============================================================

-- Prompt 模板表
CREATE TABLE IF NOT EXISTS prompt_template (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    template_code    VARCHAR(50)  NOT NULL COMMENT '模板编码（唯一标识）',
    template_name    VARCHAR(100) NOT NULL COMMENT '模板名称',
    scenario         VARCHAR(50)  NOT NULL COMMENT '使用场景（sql_generation/chart_generation 等）',
    content          TEXT         NOT NULL COMMENT '当前活跃版本的模板内容（冗余，加速读取）',
    current_version  INT          NOT NULL DEFAULT 1 COMMENT '当前活跃版本号',
    enabled          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    version          INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE INDEX uk_template_code (template_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Prompt 模板表';

-- Prompt 模板版本表
CREATE TABLE IF NOT EXISTS prompt_template_version (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    template_id     BIGINT       NOT NULL COMMENT '关联的模板ID',
    version_no      INT          NOT NULL COMMENT '版本号',
    content         TEXT         NOT NULL COMMENT '该版本的模板内容',
    change_summary  VARCHAR(500) NULL COMMENT '修改摘要',
    is_active       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否为当前活跃版本',
    created_by      BIGINT       NULL COMMENT '创建人用户ID',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_version_template (template_id),
    UNIQUE INDEX uk_template_version (template_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Prompt 模板版本表';
