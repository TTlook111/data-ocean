-- V38: 业务术语表（Glossary + GlossaryTerm）
-- 建立业务术语体系，让 NL2SQL 理解用户语言。
-- 术语有层级（如"财务指标"→"营收"→"月度营收"），支持同义词扩展。

-- 术语表（顶级分类）
CREATE TABLE IF NOT EXISTS glossary (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    name            VARCHAR(128) NOT NULL COMMENT '术语表名称（唯一）',
    display_name    VARCHAR(256) COMMENT '显示名称',
    description     TEXT COMMENT '术语表描述',
    owner_id        BIGINT COMMENT '负责人 ID',
    status          VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT / PUBLISHED',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_glossary_name (name),
    INDEX idx_glossary_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务术语表';

-- 术语条目（支持三级嵌套：术语表 → 术语 → 子术语）
CREATE TABLE IF NOT EXISTS glossary_term (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    glossary_id     BIGINT NOT NULL COMMENT '所属术语表 ID',
    parent_id       BIGINT COMMENT '父术语 ID（NULL 表示顶级术语）',
    name            VARCHAR(128) NOT NULL COMMENT '术语名称',
    display_name    VARCHAR(256) COMMENT '显示名称',
    description     TEXT COMMENT '术语描述',
    synonyms        JSON COMMENT '同义词列表，如 ["营收", "收入", "Revenue"]',
    related_terms   JSON COMMENT '关联术语 ID 列表',
    fqn             VARCHAR(512) NOT NULL COMMENT '全限定名：glossary.术语表.术语',
    status          VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT / PENDING_REVIEW / APPROVED / REJECTED',
    reviewer_id     BIGINT COMMENT '审核人 ID',
    reviewed_at     DATETIME COMMENT '审核时间',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_term_fqn (fqn),
    INDEX idx_term_glossary (glossary_id),
    INDEX idx_term_parent (parent_id),
    INDEX idx_term_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务术语条目';
