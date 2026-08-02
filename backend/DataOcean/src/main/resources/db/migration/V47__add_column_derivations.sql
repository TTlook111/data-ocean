-- ============================================================
-- V47: 列级派生关系存储
-- 为 query_task 表添加 column_derivations JSON 字段，
-- 存储 Python sqlglot AST 提取的列→列血缘关系。
-- 对应 Phase 1 DERIVED_FROM 实现。
-- ============================================================

ALTER TABLE query_task
    ADD COLUMN column_derivations JSON NULL COMMENT '列级派生关系（sqlglot AST 提取）' AFTER used_columns;
