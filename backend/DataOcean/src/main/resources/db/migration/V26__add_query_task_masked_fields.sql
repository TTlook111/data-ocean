-- V26: 为 query_task 添加 masked_fields 字段
-- 存储 Python AST 标记的需脱敏字段（含输出别名映射）
ALTER TABLE query_task ADD COLUMN masked_fields VARCHAR(2000) NULL COMMENT 'Python AST 标记的需脱敏字段 JSON' AFTER used_columns;
