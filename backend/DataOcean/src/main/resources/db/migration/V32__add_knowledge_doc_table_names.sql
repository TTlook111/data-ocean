-- ============================================================
-- knowledge_doc 表新增 table_names 字段
-- 记录该文档覆盖的表名列表（JSON 数组）
-- ============================================================

ALTER TABLE knowledge_doc ADD COLUMN table_names TEXT COMMENT '该文档覆盖的表名列表（JSON 数组）';
