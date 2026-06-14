-- V37: 统一实体-关系图谱（借鉴 OpenMetadata Entity-Relationship 模型）
-- 为元数据治理提供统一的实体注册表和关系边表，替代孤立的表/列存储。

-- 统一实体注册表
CREATE TABLE IF NOT EXISTS metadata_entity (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    entity_type     VARCHAR(32) NOT NULL COMMENT '实体类型：DATASOURCE, DATABASE, TABLE, COLUMN, GLOSSARY_TERM, TAG',
    entity_uuid     CHAR(36) NOT NULL COMMENT '实体 UUID',
    fqn             VARCHAR(512) NOT NULL COMMENT '全限定名：datasource.db.table.column',
    name            VARCHAR(256) NOT NULL COMMENT '实体名称',
    display_name    VARCHAR(256) COMMENT '显示名称',
    description     TEXT COMMENT '实体描述',
    entity_metadata JSON COMMENT '实体扩展元数据',
    owner_id        BIGINT COMMENT '负责人 ID',
    version         INT NOT NULL DEFAULT 1 COMMENT '版本号',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_entity_uuid (entity_uuid),
    INDEX idx_entity_type (entity_type),
    INDEX idx_entity_fqn (fqn),
    FULLTEXT INDEX ft_entity_search (name, display_name, description)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一实体注册表';

-- 统一关系边表
CREATE TABLE IF NOT EXISTS metadata_relationship (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_id       BIGINT NOT NULL COMMENT '源实体 ID',
    source_type     VARCHAR(32) NOT NULL COMMENT '源实体类型',
    target_id       BIGINT NOT NULL COMMENT '目标实体 ID',
    target_type     VARCHAR(32) NOT NULL COMMENT '目标实体类型',
    relation_type   VARCHAR(32) NOT NULL COMMENT '关系类型：CONTAINS, HAS_PART, LINEAGE, TAGGED_WITH, GLOSSARY_OF, FOREIGN_KEY, DERIVED_FROM, RELATED_TO',
    relation_metadata JSON COMMENT '关系扩展元数据（如血缘类型、列映射等）',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_relationship (source_id, target_id, relation_type),
    INDEX idx_rel_source (source_id, relation_type),
    INDEX idx_rel_target (target_id, relation_type),
    INDEX idx_rel_type (relation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一关系边表';
