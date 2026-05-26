-- =====================================================
-- V13: 创建 skills.md 业务知识库相关表
-- 包含：knowledge_doc, knowledge_doc_version,
--       knowledge_chunk, knowledge_review_task, vector_index_task
-- =====================================================

-- 知识文档主表（每个数据源对应一份 skills.md）
CREATE TABLE knowledge_doc (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    datasource_id   BIGINT       NOT NULL COMMENT '关联数据源 ID',
    title           VARCHAR(200) NOT NULL COMMENT '文档标题',
    content         LONGTEXT              COMMENT '当前版本内容（Markdown）',
    current_version INT          NOT NULL DEFAULT 0 COMMENT '当前版本号',
    status          VARCHAR(30)  NOT NULL DEFAULT 'DRAFT' COMMENT '文档状态: DRAFT/PENDING_REVIEW/APPROVED/PUBLISHED/DEPRECATED',
    review_status   VARCHAR(30)           COMMENT '审核状态: PENDING/APPROVED/REJECTED',
    updated_by      BIGINT                COMMENT '最后更新人 ID',
    version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记: 0=正常, 1=已删除',
    PRIMARY KEY (id),
    INDEX idx_knowledge_doc_datasource (datasource_id),
    INDEX idx_knowledge_doc_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档主表';

-- 知识文档版本表（记录每次编辑/生成的历史版本）
CREATE TABLE knowledge_doc_version (
    id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    doc_id               BIGINT       NOT NULL COMMENT '关联文档 ID',
    datasource_id        BIGINT       NOT NULL COMMENT '关联数据源 ID',
    metadata_snapshot_id BIGINT                COMMENT '关联元数据快照 ID',
    version_no           INT          NOT NULL COMMENT '版本号',
    content              LONGTEXT     NOT NULL COMMENT '版本内容（Markdown）',
    generation_source    VARCHAR(30)  NOT NULL DEFAULT 'MANUAL' COMMENT '生成来源: MANUAL/AI_GENERATED/ROLLBACK',
    review_status        VARCHAR(30)  NOT NULL DEFAULT 'PENDING' COMMENT '审核状态: PENDING/APPROVED/REJECTED',
    reviewer_id          BIGINT                COMMENT '审核人 ID',
    change_summary       VARCHAR(500)          COMMENT '变更摘要',
    created_by           BIGINT                COMMENT '创建人 ID',
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_doc_version_doc (doc_id),
    INDEX idx_doc_version_datasource (datasource_id),
    UNIQUE INDEX uk_doc_version (doc_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档版本表';

-- 知识切片表（发布后按模块切分的 RAG 检索单元）
CREATE TABLE knowledge_chunk (
    id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    doc_id               BIGINT       NOT NULL COMMENT '关联文档 ID',
    version_no           INT          NOT NULL COMMENT '关联版本号',
    metadata_snapshot_id BIGINT                COMMENT '关联元数据快照 ID',
    chunk_type           VARCHAR(30)  NOT NULL COMMENT '切片类型: TABLE_DESC/JOIN_PATH/METRIC/FIELD_NOTE',
    chunk_text           TEXT         NOT NULL COMMENT '切片文本内容',
    related_table        VARCHAR(200)          COMMENT '关联表名',
    related_column       VARCHAR(200)          COMMENT '关联字段名',
    review_status        VARCHAR(30)  NOT NULL DEFAULT 'APPROVED' COMMENT '审核状态',
    vector_status        VARCHAR(30)  NOT NULL DEFAULT 'PENDING' COMMENT '向量化状态: PENDING/INDEXED/FAILED',
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_chunk_doc (doc_id),
    INDEX idx_chunk_vector_status (vector_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识切片表';

-- 知识审核任务表
CREATE TABLE knowledge_review_task (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    doc_version_id  BIGINT       NOT NULL COMMENT '关联文档版本 ID',
    reviewer_id     BIGINT                COMMENT '审核人 ID',
    review_status   VARCHAR(30)  NOT NULL DEFAULT 'PENDING' COMMENT '审核状态: PENDING/APPROVED/REJECTED',
    review_comment  VARCHAR(1000)         COMMENT '审核意见',
    submitted_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交审核时间',
    reviewed_at     DATETIME              COMMENT '审核完成时间',
    PRIMARY KEY (id),
    INDEX idx_review_task_version (doc_version_id),
    INDEX idx_review_task_status (review_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识审核任务表';

-- 向量化任务表
CREATE TABLE vector_index_task (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    datasource_id   BIGINT       NOT NULL COMMENT '关联数据源 ID',
    target_type     VARCHAR(30)  NOT NULL COMMENT '目标类型: KNOWLEDGE_DOC/KNOWLEDGE_CHUNK',
    target_id       BIGINT       NOT NULL COMMENT '目标 ID（文档或切片 ID）',
    status          VARCHAR(30)  NOT NULL DEFAULT 'PENDING' COMMENT '任务状态: PENDING/PROCESSING/COMPLETED/FAILED',
    started_at      DATETIME              COMMENT '开始处理时间',
    finished_at     DATETIME              COMMENT '完成时间',
    error_message   VARCHAR(1000)         COMMENT '错误信息',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_vector_task_status (status),
    INDEX idx_vector_task_datasource (datasource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='向量化任务表';

-- 添加知识库管理权限
INSERT INTO sys_permission (permission_code, permission_name, module, description)
VALUES ('knowledge:manage', '知识库管理', 'knowledge', '管理 skills.md 文档的编辑、审核和发布')
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module = VALUES(module),
    description = VALUES(description);

-- 为管理员角色绑定知识库管理权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 5, id FROM sys_permission WHERE permission_code = 'knowledge:manage'
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);
