package com.dataocean.module.knowledge.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.entity.TableRelation;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataRelationshipMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates reviewed Markdown fact markers against the authoritative snapshot and lineage rows. */
@Service
@RequiredArgsConstructor
public class KnowledgeSnapshotFactValidator {
    private static final Pattern FACT_MARKER = Pattern.compile(
            "<!--\\s*dataocean-fact:\\s*(\\{.*?})\\s*-->", Pattern.DOTALL);

    private final DbTableMetaMapper tableMapper;
    private final DbColumnMetaMapper columnMapper;
    private final TableRelationMapper tableRelationMapper;
    private final MetadataRelationshipMapper relationshipMapper;
    private final ObjectMapper objectMapper;

    public void validate(Long datasourceId, Long snapshotId, String content) {
        if (datasourceId == null || snapshotId == null || content == null || content.isBlank()) {
            throw new BusinessException("知识文档缺少数据源、来源快照或内容");
        }
        List<DbTableMeta> tables = tableMapper.selectList(new LambdaQueryWrapper<DbTableMeta>()
                .eq(DbTableMeta::getDatasourceId, datasourceId)
                .eq(DbTableMeta::getSnapshotId, snapshotId));
        if (tables == null || tables.isEmpty()) {
            throw new BusinessException("所选元数据快照没有表结构");
        }
        Map<Long, DbTableMeta> tableById = new HashMap<>();
        Map<Long, DbColumnMeta> columnById = new HashMap<>();
        Map<String, Long> tableIdByName = new HashMap<>();
        List<Long> tableIds = new ArrayList<>();
        for (DbTableMeta table : tables) {
            tableById.put(table.getId(), table);
            tableIdByName.put(table.getTableName().toLowerCase(), table.getId());
            tableIds.add(table.getId());
        }
        List<DbColumnMeta> columns = columnMapper.selectList(new LambdaQueryWrapper<DbColumnMeta>()
                .in(DbColumnMeta::getTableMetaId, tableIds)
                .eq(DbColumnMeta::getDatasourceId, datasourceId)
                .eq(DbColumnMeta::getSnapshotId, snapshotId));
        for (DbColumnMeta column : columns) columnById.put(column.getId(), column);

        Set<Long> seenTables = new HashSet<>();
        Set<Long> seenColumns = new HashSet<>();
        Matcher matcher = FACT_MARKER.matcher(content);
        int markerCount = 0;
        int previousMarkerEnd = 0;
        while (matcher.find()) {
            markerCount++;
            Map<String, Object> fact = parse(matcher.group(1));
            if (!snapshotId.equals(asLong(fact.get("snapshotId")))) {
                throw new BusinessException("知识事实 marker 混入其他 snapshotId");
            }
            List<Long> sourceIds = numberList(fact.get("sourceIds"));
            List<String> dependencies = stringList(fact.get("dependencies"));
            if (sourceIds.isEmpty() || dependencies.isEmpty()) {
                throw new BusinessException("知识事实 marker 缺少来源 ID 或资源依赖");
            }
            String type = String.valueOf(fact.getOrDefault("factType", ""));
            String review = String.valueOf(fact.getOrDefault("reviewStatus", "")).toUpperCase();
            int nextMarker = content.indexOf("<!-- dataocean-fact:", matcher.end());
            String factBody = content.substring(previousMarkerEnd,
                    nextMarker < 0 ? content.length() : nextMarker);
            previousMarkerEnd = matcher.end();
            if (!Set.of("APPROVED", "PENDING", "REJECTED").contains(review)) {
                throw new BusinessException("知识事实 marker 审核状态无效");
            }
            switch (type) {
                case "TABLE_STRUCTURE" -> validateTableStructure(sourceIds, dependencies, fact, factBody, tableById, seenTables);
                case "COLUMN_STRUCTURE" -> validateColumnStructure(sourceIds, dependencies, fact, factBody,
                        tableById, columnById, seenColumns);
                case "TABLE_COMMENT" -> validateTableComment(sourceIds, dependencies, fact, tableById);
                case "COLUMN_COMMENT" -> validateColumnComment(sourceIds, dependencies, fact, tableById, columnById);
                case "JOIN_PATH", "JOIN_CANDIDATE" -> validateTableRelation(
                        sourceIds, dependencies, fact, factBody, datasourceId, snapshotId, type);
                case "LINEAGE", "DERIVED_FROM" -> validateLineage(
                        sourceIds, dependencies, fact, factBody, snapshotId, type);
                default -> throw new BusinessException("知识文档包含无来源的事实类型：" + type);
            }
        }
        if (markerCount == 0 || !seenTables.equals(tableById.keySet()) || !seenColumns.equals(columnById.keySet())) {
            throw new BusinessException("知识文档未完整覆盖所选快照全部表和字段");
        }
    }

    private void validateTableStructure(List<Long> sourceIds, List<String> dependencies,
                                       Map<String, Object> fact, String factBody,
                                       Map<Long, DbTableMeta> tableById,
                                       Set<Long> seen) {
        if (sourceIds.size() != 1) throw new BusinessException("表结构 marker 的来源 ID 数量无效");
        DbTableMeta table = tableById.get(sourceIds.get(0));
        if (table == null || !seen.add(table.getId())) throw new BusinessException("表结构 marker 遗漏、重复或错属表");
        if (!Set.of("table:" + table.getTableName().toLowerCase()).equals(new HashSet<>(dependencies))) {
            throw new BusinessException("表结构 marker 的资源依赖与快照不一致");
        }
        assertGovernance(fact, table.getGovernanceStatus());
        String tableType = table.getTableType() == null ? "UNKNOWN" : table.getTableType();
        if (!factBody.contains(table.getTableName())
                || !factBody.contains("Snapshot table ID: `" + table.getId() + "`")) {
            throw new BusinessException("表名或表 ID 与本次快照不一致");
        }
        if (!factBody.contains("Table type: `" + html(tableType) + "`")) {
            throw new BusinessException("表类型与本次快照不一致");
        }
    }

    private void validateColumnStructure(List<Long> sourceIds, List<String> dependencies,
                                        Map<String, Object> fact, String factBody,
                                        Map<Long, DbTableMeta> tableById,
                                        Map<Long, DbColumnMeta> columnById, Set<Long> seen) {
        if (sourceIds.size() != 2) throw new BusinessException("字段结构 marker 必须有表和字段 ID");
        DbTableMeta table = tableById.get(sourceIds.get(0));
        DbColumnMeta column = columnById.get(sourceIds.get(1));
        if (table == null || column == null || !table.getId().equals(column.getTableMetaId())
                || !seen.add(column.getId())) {
            throw new BusinessException("字段结构 marker 遗漏、重复或错属表");
        }
        Set<String> expected = Set.of(
                "table:" + table.getTableName().toLowerCase(),
                "column:" + table.getTableName().toLowerCase() + "." + column.getColumnName().toLowerCase());
        if (!expected.equals(new HashSet<>(dependencies))) {
            throw new BusinessException("字段结构 marker 的资源依赖与快照不一致");
        }
        assertGovernance(fact, column.getGovernanceStatus());
        String type = column.getDataType() == null ? "UNKNOWN" : column.getDataType();
        if (!factBody.contains(column.getColumnName())
                || !factBody.contains("Column ID: `" + column.getId() + "`")
                || !factBody.contains("Type: `" + html(type) + "`")) {
            throw new BusinessException("字段名称、ID 或类型与本次快照不一致");
        }
    }

    private void validateTableComment(List<Long> sourceIds, List<String> dependencies,
                                     Map<String, Object> fact, Map<Long, DbTableMeta> tableById) {
        if (sourceIds.size() != 1) throw new BusinessException("表注释 marker 来源无效");
        DbTableMeta table = tableById.get(sourceIds.get(0));
        if (table == null || !Set.of("table:" + table.getTableName().toLowerCase()).equals(new HashSet<>(dependencies))) {
            throw new BusinessException("表注释 marker 来源与快照不一致");
        }
        assertGovernance(fact, table.getGovernanceStatus());
    }

    private void validateColumnComment(List<Long> sourceIds, List<String> dependencies,
                                      Map<String, Object> fact, Map<Long, DbTableMeta> tableById,
                                      Map<Long, DbColumnMeta> columnById) {
        if (sourceIds.size() != 2) throw new BusinessException("字段注释 marker 来源无效");
        DbTableMeta table = tableById.get(sourceIds.get(0));
        DbColumnMeta column = columnById.get(sourceIds.get(1));
        if (table == null || column == null || !table.getId().equals(column.getTableMetaId())) {
            throw new BusinessException("字段注释 marker 来源与快照不一致");
        }
        Set<String> expected = Set.of(
                "table:" + table.getTableName().toLowerCase(),
                "column:" + table.getTableName().toLowerCase() + "." + column.getColumnName().toLowerCase());
        if (!expected.equals(new HashSet<>(dependencies))) throw new BusinessException("字段注释依赖与快照不一致");
        assertGovernance(fact, column.getGovernanceStatus());
    }

    private void validateTableRelation(List<Long> sourceIds, List<String> dependencies,
                                      Map<String, Object> fact, String marker,
                                      Long datasourceId, Long snapshotId, String factType) {
        if (sourceIds.size() != 1) throw new BusinessException("Join 事实来源 ID 无效");
        TableRelation relation = tableRelationMapper.selectById(sourceIds.get(0));
        if (relation == null || !datasourceId.equals(relation.getDatasourceId())
                || !snapshotId.equals(relation.getSnapshotId())) {
            throw new BusinessException("Join 事实不属于本次元数据快照");
        }
        boolean confirmed = TableRelation.TYPE_FK.equals(relation.getRelationType())
                || (TableRelation.TYPE_MANUAL.equals(relation.getRelationType())
                && "CONFIRMED".equalsIgnoreCase(relation.getReviewStatus()));
        if ("JOIN_PATH".equals(factType) && (!confirmed || !"APPROVED".equalsIgnoreCase(String.valueOf(fact.get("reviewStatus"))))) {
            throw new BusinessException("未确认关系不能发布为 Join Path");
        }
        if ("JOIN_CANDIDATE".equals(factType) && (confirmed || !"PENDING".equalsIgnoreCase(String.valueOf(fact.get("reviewStatus"))))) {
            throw new BusinessException("待审核候选关系标记与快照状态不一致");
        }
        Set<String> expected = new HashSet<>(List.of(
                "table:" + relation.getSourceTable().toLowerCase(),
                "table:" + relation.getTargetTable().toLowerCase(),
                "column:" + relation.getSourceTable().toLowerCase() + "." + relation.getSourceColumn().toLowerCase(),
                "column:" + relation.getTargetTable().toLowerCase() + "." + relation.getTargetColumn().toLowerCase()));
        if (!expected.equals(new HashSet<>(dependencies))) throw new BusinessException("Join 依赖与关系事实不一致");
        String condition = relation.getSourceTable() + "." + relation.getSourceColumn()
                + " = " + relation.getTargetTable() + "." + relation.getTargetColumn();
        if (!marker.contains(condition)) throw new BusinessException("Join 条件与来源关系不一致");
    }

    private void validateLineage(List<Long> sourceIds, List<String> dependencies,
                                 Map<String, Object> fact, String marker,
                                 Long snapshotId, String factType) {
        if (sourceIds.size() != 1) throw new BusinessException("血缘事实来源 ID 无效");
        MetadataRelationship relationship = relationshipMapper.selectById(sourceIds.get(0));
        if (relationship == null || !factType.equals(relationship.getRelationType())) {
            throw new BusinessException("血缘事实来源记录不存在或类型不一致");
        }
        Map<String, Object> metadata = parse(relationship.getRelationMetadata());
        if (!"CONFIRMED".equalsIgnoreCase(String.valueOf(metadata.get("confirmation_status")))
                || !"BOUND".equalsIgnoreCase(String.valueOf(metadata.get("binding_status")))
                || !String.valueOf(snapshotId).equals(String.valueOf(metadata.get("bound_snapshot_id")))) {
            throw new BusinessException("血缘事实尚未确认或未绑定到本次快照");
        }
        String sourceFqn = String.valueOf(metadata.get("source_fqn"));
        String targetFqn = String.valueOf(metadata.get("target_fqn"));
        if (!marker.contains(sourceFqn) || !marker.contains(targetFqn)) {
            throw new BusinessException("血缘文档端点与来源实体不一致");
        }
        Set<String> expected = new HashSet<>(lineageDependencies(sourceFqn, targetFqn));
        if (!expected.equals(new HashSet<>(dependencies))) throw new BusinessException("血缘资源依赖与来源实体不一致");
    }

    private List<String> lineageDependencies(String sourceFqn, String targetFqn) {
        List<String> result = new ArrayList<>();
        addFqnDependencies(result, sourceFqn);
        addFqnDependencies(result, targetFqn);
        return result.stream().distinct().toList();
    }

    private void addFqnDependencies(List<String> result, String fqn) {
        String[] parts = fqn.split("\\.");
        if (parts.length >= 3) result.add("table:" + parts[parts.length - 2].toLowerCase());
        if (parts.length >= 4) result.add("column:" + parts[parts.length - 2].toLowerCase()
                + "." + parts[parts.length - 1].toLowerCase());
    }

    private void assertGovernance(Map<String, Object> fact, String sourceStatus) {
        String expected = sourceStatus == null ? "DISCOVERED" : sourceStatus;
        if (!expected.equalsIgnoreCase(String.valueOf(fact.get("governanceStatus")))) {
            throw new BusinessException("事实 marker 治理状态与本次元数据快照不一致");
        }
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new BusinessException("知识事实 marker JSON 无效");
        }
    }

    private List<Long> numberList(Object raw) {
        if (!(raw instanceof List<?> values)) return List.of();
        List<Long> result = new ArrayList<>();
        for (Object value : values) {
            try {
                result.add(value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value)));
            } catch (Exception e) {
                throw new BusinessException("知识事实来源 ID 无效");
            }
        }
        return result;
    }

    private List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> values)) return List.of();
        return values.stream().map(String::valueOf).toList();
    }

    private Long asLong(Object value) {
        try {
            return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private String html(String value) {
        return org.springframework.web.util.HtmlUtils.htmlEscape(value);
    }
}
