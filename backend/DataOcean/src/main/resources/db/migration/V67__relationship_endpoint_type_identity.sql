-- 关系端点是“类型 + ID”而不是裸 ID：术语 ID 与元数据实体 ID 来自不同表，可能数字重合。
-- 唯一键必须包含端点类型，否则类型不同但数字 ID 相同的两条关系会被误判为同一条。
ALTER TABLE metadata_relationship
    DROP INDEX uk_relationship,
    ADD UNIQUE KEY uk_relationship_endpoints
        (source_type, source_id, target_type, target_id, relation_type);
