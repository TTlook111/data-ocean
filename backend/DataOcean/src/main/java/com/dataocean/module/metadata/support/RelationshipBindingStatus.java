package com.dataocean.module.metadata.support;

import com.dataocean.module.metadata.entity.po.MetadataRelationship;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;

/**
 * 人工维护关系的绑定状态。
 *
 * <p>采集产生的结构关系随快照重建；术语映射和人工标签属于业务关联，
 * 快照发布后必须保留。目标逻辑对象消失时把关系标记为 {@code UNBOUND} 并保留历史，
 * 而不是删除后伪装成“从未关联”。</p>
 */
public final class RelationshipBindingStatus {

    /** 关系两端都能解析到当前逻辑对象。 */
    public static final String BOUND = "BOUND";

    /** 关系保留但端点已不在当前发布快照中，需要重新确认。 */
    public static final String UNBOUND = "UNBOUND";

    public static final String KEY_STATUS = "binding_status";
    public static final String KEY_REASON = "invalid_reason";
    public static final String KEY_INVALIDATED_SNAPSHOT = "invalidated_snapshot_id";
    public static final String KEY_INVALIDATED_AT = "invalidated_at";
    public static final String KEY_PREVIOUS_SOURCE_FQN = "previous_source_fqn";
    public static final String KEY_PREVIOUS_TARGET_FQN = "previous_target_fqn";
    public static final String KEY_PREVIOUS_SOURCE_DATASOURCE_ID = "previous_source_datasource_id";
    public static final String KEY_PREVIOUS_TARGET_DATASOURCE_ID = "previous_target_datasource_id";
    /** 源端点已核验所属的快照；跨源关系不能再用单个 bound_snapshot_id 表达两端。 */
    public static final String KEY_SOURCE_BOUND_SNAPSHOT_ID = "source_bound_snapshot_id";
    /** 目标端点已核验所属的快照。 */
    public static final String KEY_TARGET_BOUND_SNAPSHOT_ID = "target_bound_snapshot_id";
    /** 本次核验未通过的端点角色，如 ["SOURCE", "TARGET"]。 */
    public static final String KEY_UNVERIFIED_ENDPOINTS = "unverified_endpoints";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RelationshipBindingStatus() {
    }

    /** 关系是否已明确标记为失效。没有绑定状态的历史行按有效处理。 */
    public static boolean isInactive(MetadataRelationship relationship) {
        return relationship != null && isInactive(relationship.getRelationMetadata());
    }

    public static boolean isInactive(String relationMetadata) {
        String status = readString(relationMetadata, KEY_STATUS);
        return UNBOUND.equalsIgnoreCase(status);
    }

    /** 已确认的人工作业/ETL 血缘。 */
    public static boolean isConfirmedLineage(MetadataRelationship relationship) {
        if (relationship == null) {
            return false;
        }
        String relationType = relationship.getRelationType();
        if (!MetadataRelationship.TYPE_LINEAGE.equals(relationType)
                && !MetadataRelationship.TYPE_DERIVED_FROM.equals(relationType)) {
            return false;
        }
        String confirmation = readString(relationship.getRelationMetadata(), "confirmation_status");
        return "CONFIRMED".equalsIgnoreCase(confirmation);
    }

    /** 人工维护、需要在快照重建时保留的业务关联。 */
    public static boolean isHumanMaintained(MetadataRelationship relationship) {
        if (relationship == null) {
            return false;
        }
        return MetadataRelationship.TYPE_GLOSSARY_OF.equals(relationship.getRelationType())
                || MetadataRelationship.TYPE_TAGGED_WITH.equals(relationship.getRelationType())
                || MetadataRelationship.TYPE_RELATED_TO.equals(relationship.getRelationType())
                || isConfirmedLineage(relationship);
    }

    private static String readString(String json, String key) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> metadata = OBJECT_MAPPER.readValue(json, new TypeReference<>() {
            });
            Object value = metadata.get(key);
            return value == null ? null : Objects.toString(value, null);
        } catch (Exception ignored) {
            return null;
        }
    }
}
