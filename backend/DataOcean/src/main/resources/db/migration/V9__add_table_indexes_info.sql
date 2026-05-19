-- 为 db_table_meta 添加索引信息 JSON 字段
ALTER TABLE db_table_meta ADD COLUMN indexes_info JSON DEFAULT NULL COMMENT '索引信息JSON' AFTER index_size_bytes;
