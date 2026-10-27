package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.support.LineageDependencies;
import com.dataocean.module.metadata.entity.po.*;
import com.dataocean.module.metadata.mapper.*;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 血缘事实的当前资格和不可变身份；图谱中的跨源边不作为单源查询事实。 */
@Service
@RequiredArgsConstructor
public class LineageFactService {
    private final MetadataRelationshipMapper relationships;
    private final MetadataEntityService entities;
    private final MetadataSnapshotMapper snapshots;
    private final DbTableMetaMapper tables;
    private final DbColumnMetaMapper columns;
    private final ObjectMapper objectMapper;

    public Map<String, Object> currentReference(Long datasourceId, Long snapshotId, Long relationshipId) {
        return currentSnapshot(datasourceId, snapshotId, relationshipId).reference();
    }

    /** 同一次权威读取产生内容及其身份，调用者不能把另一版本的正文配到该摘要。 */
    public record FactSnapshot(Map<String, Object> reference, Map<String, Object> fact) {}

    public FactSnapshot currentSnapshot(Long datasourceId, Long snapshotId, Long relationshipId) {
        try {
            MetadataSnapshot snapshot = snapshots.selectById(snapshotId);
            MetadataSnapshot current = snapshots.selectOne(new LambdaQueryWrapper<MetadataSnapshot>()
                    .eq(MetadataSnapshot::getDatasourceId, datasourceId).eq(MetadataSnapshot::getStatus, "PUBLISHED")
                    .orderByDesc(MetadataSnapshot::getSnapshotVersion).last("LIMIT 1"));
            if (snapshot == null || !Objects.equals(snapshot.getDatasourceId(), datasourceId)
                    || current == null || !Objects.equals(current.getId(), snapshotId)) reject();
            MetadataRelationship relation = relationships.selectById(relationshipId);
            if (relation == null || !isLineage(relation.getRelationType())) reject();
            Map<String, Object> metadata = objectMapper.readValue(relation.getRelationMetadata(), new TypeReference<>() {});
            if (!"CONFIRMED".equals(metadata.get("confirmation_status")) || !"BOUND".equals(metadata.get("binding_status"))
                    || !String.valueOf(snapshotId).equals(String.valueOf(metadata.get("source_bound_snapshot_id")))
                    || !String.valueOf(snapshotId).equals(String.valueOf(metadata.get("target_bound_snapshot_id")))) reject();
            String expectedType = "LINEAGE".equals(relation.getRelationType()) ? "TABLE" : "COLUMN";
            MetadataEntity source = endpoint(datasourceId, snapshotId, relation.getSourceId(), expectedType, metadata.get("source_fqn"));
            MetadataEntity target = endpoint(datasourceId, snapshotId, relation.getTargetId(), expectedType, metadata.get("target_fqn"));
            if (!expectedType.equals(relation.getSourceType()) || !expectedType.equals(relation.getTargetType())) reject();
            List<String> dependencies = LineageDependencies.parse(source.getFqn(), target.getFqn());
            List<Object> identity = List.of(relation.getId(), relation.getRelationType(), source.getId(), source.getFqn(),
                    target.getId(), target.getFqn(), snapshotId, metadata);
            String canonical = objectMapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(identity);
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("sourceSnapshotId", String.valueOf(snapshotId));
            reference.put("factType", relation.getRelationType());
            reference.put("factSourceIds", List.of(String.valueOf(relationshipId)));
            reference.put("sourceFingerprint", fingerprint);
            reference.put("resourceDependencies", dependencies);
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("relationship_id", relationshipId);
            fact.put("source_fqn", source.getFqn()); fact.put("target_fqn", target.getFqn());
            fact.put("relation_type", relation.getRelationType());
            fact.put("source_snapshot_id", metadata.get("source_snapshot_id"));
            fact.put("target_snapshot_id", metadata.get("target_snapshot_id"));
            fact.put("source_bound_snapshot_id", String.valueOf(snapshotId));
            fact.put("target_bound_snapshot_id", String.valueOf(snapshotId));
            fact.put("bound_snapshot_id", snapshotId);
            fact.put("binding_status", "BOUND"); fact.put("confirmation_status", "CONFIRMED");
            fact.put("review_status", "APPROVED");
            fact.put("description", metadata.get("description"));
            fact.put("lineage_type", metadata.get("lineage_type"));
            fact.put("column_mappings", metadata.get("column_mappings"));
            fact.put("dependencies", dependencies); fact.put("source_fingerprint", fingerprint);
            return new FactSnapshot(Collections.unmodifiableMap(reference), Collections.unmodifiableMap(fact));
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("血缘事实来源或身份无效，请重新核对"); }
    }

    private MetadataEntity endpoint(Long datasourceId, Long snapshotId, Long id, String type, Object fqn) throws Exception {
        MetadataEntity entity = entities.getById(id);
        if (entity == null || !type.equals(entity.getEntityType()) || !Objects.equals(datasourceId, entities.getDatasourceIdByEntityId(id))
                || !Objects.equals(entity.getFqn(), fqn)) reject();
        String[] parts = LineageDependencies.parts(entity.getFqn());
        if (parts.length != ("TABLE".equals(type) ? 3 : 4)) reject();
        DbTableMeta table = tables.selectOne(new LambdaQueryWrapper<DbTableMeta>()
                .eq(DbTableMeta::getDatasourceId, datasourceId).eq(DbTableMeta::getSnapshotId, snapshotId).eq(DbTableMeta::getTableName, parts[2]));
        if (table == null || forbidden(table.getGovernanceStatus())) reject();
        if (parts.length == 4) {
            DbColumnMeta column = columns.selectOne(new LambdaQueryWrapper<DbColumnMeta>()
                    .eq(DbColumnMeta::getDatasourceId, datasourceId).eq(DbColumnMeta::getSnapshotId, snapshotId)
                    .eq(DbColumnMeta::getTableMetaId, table.getId()).eq(DbColumnMeta::getColumnName, parts[3]));
            if (column == null || forbidden(column.getGovernanceStatus())) reject();
        }
        return entity;
    }

    public boolean valid(Long datasourceId, Long snapshotId, String factType, List<String> ids, String fingerprint, List<String> dependencies) {
        try {
            if (ids == null || ids.size() != 1 || fingerprint == null || fingerprint.isBlank()) return false;
            Map<String, Object> current = currentReference(datasourceId, snapshotId, Long.valueOf(ids.get(0)));
            return Objects.equals(factType, current.get("factType")) && Objects.equals(fingerprint, current.get("sourceFingerprint"))
                    && new HashSet<>(dependencies).equals(new HashSet<>((List<?>) current.get("resourceDependencies")));
        } catch (RuntimeException exception) { return false; }
    }

    public boolean referencesValid(Long datasourceId, Long snapshotId, String json) {
        try {
            List<Map<String, Object>> refs = objectMapper.readValue(json, new TypeReference<>() {});
            for (Map<String, Object> ref : refs) {
                if (!String.valueOf(snapshotId).equals(ref.get("sourceSnapshotId"))
                        || !valid(datasourceId, snapshotId, (String) ref.get("factType"),
                        objectMapper.convertValue(ref.get("factSourceIds"), new TypeReference<>() {}),
                        (String) ref.get("sourceFingerprint"), objectMapper.convertValue(ref.get("resourceDependencies"), new TypeReference<>() {}))) return false;
            }
            return true;
        } catch (Exception exception) { return false; }
    }

    public static boolean isLineage(String type) { return "LINEAGE".equals(type) || "DERIVED_FROM".equals(type); }
    private boolean forbidden(String status) { return status == null || !Set.of("NORMAL", "RECOMMENDED", "SENSITIVE").contains(status); }
    private void reject() { throw new BusinessException("血缘事实已失效、端点不属于当前同源快照或依赖不完整"); }
}
