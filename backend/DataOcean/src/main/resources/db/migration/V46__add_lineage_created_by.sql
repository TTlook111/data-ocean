-- ============================================================
-- V46: 血缘管理审计字段新增
-- 为 metadata_relationship 表添加 created_by 列，
-- 用于记录血缘关系（LINEAGE/DERIVED_FROM）的操作人。
-- 与文档 data-lineage-research.md §4.1.4 审计字段存储约定一致。
-- ============================================================

ALTER TABLE metadata_relationship
    ADD COLUMN created_by VARCHAR(64) NULL COMMENT '操作人（登录用户名）' AFTER created_at;
