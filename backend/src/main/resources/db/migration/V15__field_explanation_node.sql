-- 字段生成固定列修订，既有表说明任务的列修订保持为空。
ALTER TABLE gov_generation_target
    ADD COLUMN column_revision_id BIGINT UNSIGNED NULL AFTER object_revision_id;

-- 字段解释由管理员独立分配已接入模型。
INSERT INTO mdl_node_assignment (node_code) VALUES ('field-explanation');
