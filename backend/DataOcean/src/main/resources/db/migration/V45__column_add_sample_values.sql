-- Phase 2 #12: 列元数据采样值增强
-- PET-SQL (arXiv:2403.09732) 证明采样值可显著提升 NL2SQL 准确度

ALTER TABLE db_column_meta ADD COLUMN sample_values TEXT DEFAULT NULL COMMENT '列采样值（逗号分隔，最多5个，每个最长50字符）';
