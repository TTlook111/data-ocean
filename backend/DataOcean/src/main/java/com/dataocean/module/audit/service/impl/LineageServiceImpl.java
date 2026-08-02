package com.dataocean.module.audit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.audit.entity.QueryLineageColumn;
import com.dataocean.module.audit.entity.QueryLineageTable;
import com.dataocean.module.audit.entity.vo.ImpactAnalysisVO;
import com.dataocean.module.audit.entity.vo.LineageColumnVO;
import com.dataocean.module.audit.entity.vo.LineageTableVO;
import com.dataocean.module.audit.mapper.QueryLineageColumnMapper;
import com.dataocean.module.audit.mapper.QueryLineageTableMapper;
import com.dataocean.module.audit.service.LineageService;
import com.dataocean.module.datasource.service.DatasourceAccessService;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LineageServiceImpl implements LineageService {

    private final QueryLineageTableMapper tableMapper;
    private final QueryLineageColumnMapper columnMapper;
    private final QueryTaskMapper queryTaskMapper;
    private final DatasourceAccessService datasourceAccessService;
    private final MetadataEntityService entityService;
    private final MetadataRelationshipService relationshipService;
    private final ObjectMapper objectMapper;

    @Override
    @Async
    public void saveLineage(Long queryTaskId, String usedTables, String usedColumns, String columnDerivations) {
        try {
            List<TableRef> tableRefs = parseTableRefs(usedTables);
            Set<String> tableNames = tableRefs.stream()
                    .map(TableRef::tableName)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!tableRefs.isEmpty()) {
                List<QueryLineageTable> batch = new ArrayList<>(tableRefs.size());
                for (TableRef tableRef : tableRefs) {
                    QueryLineageTable lineage = new QueryLineageTable();
                    lineage.setQueryTaskId(queryTaskId);
                    lineage.setSourceTable(tableRef.tableName());
                    lineage.setRelationType(tableRef.relationType());
                    lineage.setCreatedAt(LocalDateTime.now());
                    batch.add(lineage);
                }
                tableMapper.insert(batch);
            }

            List<ColumnRef> columns = parseColumnRefs(usedColumns, tableNames);
            if (!columns.isEmpty()) {
                List<QueryLineageColumn> batch = new ArrayList<>(columns.size());
                for (ColumnRef col : columns) {
                    QueryLineageColumn lineage = new QueryLineageColumn();
                    lineage.setQueryTaskId(queryTaskId);
                    lineage.setSourceTable(col.tableName());
                    lineage.setSourceColumn(col.columnName());
                    lineage.setExpression(col.expression());
                    lineage.setAliasName(col.aliasName());
                    lineage.setCreatedAt(LocalDateTime.now());
                    batch.add(lineage);
                }
                columnMapper.insert(batch);
            }
            log.debug("lineage saved queryTaskId={} tables={} columns={}", queryTaskId, tableNames.size(), columns.size());

            // 解析列级派生关系（Phase 1 新增）
            List<ColumnDerivation> derivations = parseColumnDerivations(columnDerivations);

            // 桥接到 metadata_relationship：LINEAGE 边 + DERIVED_FROM 边
            bridgeToEntityGraph(queryTaskId, tableRefs, columns, derivations);
        } catch (Exception e) {
            log.error("lineage save failed queryTaskId={}", queryTaskId, e);
        }
    }

    /**
     * 将查询级血缘桥接到实体关系图谱
     * <p>
     * 对于同一查询中引用的多个表，在 FROM 表和 JOIN 表之间创建 LINEAGE 关系。
     * 同时消费 Python sqlglot 提取的列级派生关系创建 DERIVED_FROM 边。
     * 边的方向统一为 source（上游）→ target（下游）。
     * relation_metadata 包含 lineage_type=QUERY、query_task_id、column_mappings。
     * </p>
     *
     * @param queryTaskId  查询任务ID
     * @param tableRefs    表引用列表
     * @param columns      列引用列表
     * @param derivations  列级派生关系列表（Phase 1 新增，来自 sqlglot AST）
     */
    private void bridgeToEntityGraph(Long queryTaskId, List<TableRef> tableRefs,
                                     List<ColumnRef> columns, List<ColumnDerivation> derivations) {
        // 加载查询任务信息
        QueryTask task = queryTaskMapper.selectById(queryTaskId);
        if (task == null) return;
        Long datasourceId = task.getDatasourceId();

        // 构建列映射信息
        List<Map<String, Object>> columnMappings = new ArrayList<>();
        for (ColumnRef col : columns) {
            Map<String, Object> mapping = new HashMap<>();
            mapping.put("from", List.of(col.tableName() + "." + col.columnName()));
            mapping.put("expression", col.expression());
            columnMappings.add(mapping);
        }

        // Phase 1: 消费列级派生关系，创建 DERIVED_FROM 边
        if (derivations != null && !derivations.isEmpty()) {
            createDerivedFromEdgesForQuery(datasourceId, task.getQuestion(), derivations);
        }

        // 单表查询时仅创建 DERIVED_FROM 边，不创建表间 LINEAGE 边
        if (tableRefs.size() < 2) return;

        // 找到 FROM 表作为上游源（数据从 FROM 表流出）
        String fromTable = tableRefs.stream()
                .filter(t -> "FROM".equals(t.relationType()))
                .map(TableRef::tableName)
                .findFirst()
                .orElse(tableRefs.get(0).tableName());

        // 为每个 JOIN/SUBQUERY 表创建 LINEAGE 关系
        String fromFqnPrefix = findFqnPrefix(datasourceId, fromTable);
        if (fromFqnPrefix == null) return;

        // 上游实体：FROM 表
        MetadataEntity fromEntity = entityService.getByFqn(fromFqnPrefix + "." + fromTable.toLowerCase());
        if (fromEntity == null) return;

        for (TableRef ref : tableRefs) {
            if (ref.tableName().equalsIgnoreCase(fromTable)) continue;
            if (!"JOIN".equals(ref.relationType()) && !"SUBQUERY".equals(ref.relationType())) continue;

            String toFqnPrefix = findFqnPrefix(datasourceId, ref.tableName());
            if (toFqnPrefix == null) continue;

            // 下游实体：JOIN/SUBQUERY 表
            MetadataEntity toEntity = entityService.getByFqn(toFqnPrefix + "." + ref.tableName().toLowerCase());
            if (toEntity == null) continue;

            // 创建 LINEAGE 关系：FROM（上游 source）→ JOIN（下游 target）
            MetadataRelationship rel = new MetadataRelationship();
            rel.setSourceId(fromEntity.getId());
            rel.setSourceType(MetadataEntity.TYPE_TABLE);
            rel.setTargetId(toEntity.getId());
            rel.setTargetType(MetadataEntity.TYPE_TABLE);
            rel.setRelationType(MetadataRelationship.TYPE_LINEAGE);

            String metadata = "{\"lineage_type\":\"QUERY\",\"query_task_id\":" + queryTaskId
                    + ",\"sql_query\":" + toJson(task.getQuestion())
                    + ",\"column_mappings\":" + toJson(columnMappings) + "}";
            rel.setRelationMetadata(metadata);

            // 设置操作人（表列优先）
            try {
                rel.setCreatedBy(com.dataocean.common.security.UserContext.currentUser().getUsername());
            } catch (Exception e) {
                rel.setCreatedBy("system");
            }

            try {
                relationshipService.upsert(rel);
            } catch (Exception e) {
                log.debug("LINEAGE 关系创建跳过 from={} to={} error={}", fromEntity.getFqn(), toEntity.getFqn(), e.getMessage());
            }
        }
    }

    /**
     * 消费 Python sqlglot 提取的列级派生关系，创建 DERIVED_FROM 边。
     * <p>
     * 对于每条派生关系，通过 FQN 查找源列和目标列的 metadata_entity，
     * 若两者均存在，则创建 COLUMN → COLUMN 的 DERIVED_FROM 边。
     * 单表查询的列派生关系也通过此方法处理。
     * </p>
     */
    private void createDerivedFromEdgesForQuery(Long datasourceId, String question,
                                                 List<ColumnDerivation> derivations) {
        int createdCount = 0;
        for (ColumnDerivation d : derivations) {
            String sourceFqn = buildColumnFqn(datasourceId, d.sourceTable(), d.sourceColumn());
            String targetFqn = buildColumnFqn(datasourceId, d.targetTable(), d.targetColumn());

            if (sourceFqn == null || targetFqn == null) continue;

            MetadataEntity sourceCol = entityService.getByFqn(sourceFqn);
            MetadataEntity targetCol = entityService.getByFqn(targetFqn);

            if (sourceCol == null || targetCol == null) continue;

            // 构建 DERIVED_FROM 的 relation_metadata
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("expression_type", d.expressionType() != null ? d.expressionType() : "DIRECT");
            if (d.expression() != null && !d.expression().isBlank()) {
                meta.put("expression", d.expression());
            }
            if (d.targetAlias() != null && !d.targetAlias().isBlank()) {
                meta.put("target_alias", d.targetAlias());
            }
            meta.put("source", "SQL_PARSER"); // sqlglot 自动提取来源
            meta.put("description", question != null ? question.substring(0, Math.min(question.length(), 200)) : "");

            // 对齐 OpenLineage ColumnLineageDatasetFacet
            Map<String, Object> transformation = new LinkedHashMap<>();
            transformation.put("type", mapExpressionTypeToOpenLineage(d.expressionType()));
            transformation.put("subtype", d.expressionType());
            if (d.expression() != null) {
                transformation.put("description", d.expression());
            }
            meta.put("transformation", transformation);

            MetadataRelationship derivedRel = new MetadataRelationship();
            derivedRel.setSourceId(sourceCol.getId());
            derivedRel.setSourceType(MetadataEntity.TYPE_COLUMN);
            derivedRel.setTargetId(targetCol.getId());
            derivedRel.setTargetType(MetadataEntity.TYPE_COLUMN);
            derivedRel.setRelationType(MetadataRelationship.TYPE_DERIVED_FROM);

            try {
                derivedRel.setRelationMetadata(objectMapper.writeValueAsString(meta));
                try {
                    derivedRel.setCreatedBy(
                            com.dataocean.common.security.UserContext.currentUser().getUsername());
                } catch (Exception e) {
                    derivedRel.setCreatedBy("system");
                }
                relationshipService.upsert(derivedRel);
                createdCount++;
            } catch (Exception e) {
                log.debug("DERIVED_FROM 创建跳过 source={} target={} error={}",
                        sourceFqn, targetFqn, e.getMessage());
            }
        }
        if (createdCount > 0) {
            log.info("已创建 {} 条 DERIVED_FROM 边（SQL_PARSER 来源） datasourceId={}", createdCount, datasourceId);
        }
    }

    /** 映射 expression_type 到 OpenLineage transformation type */
    private String mapExpressionTypeToOpenLineage(String expressionType) {
        if (expressionType == null) return "IDENTITY";
        return switch (expressionType) {
            case "DIRECT" -> "IDENTITY";
            case "AGGREGATION" -> "TRANSFORMATION";
            default -> "TRANSFORMATION";
        };
    }

    /** 通过 FQN 查找列实体 */
    private String buildColumnFqn(Long datasourceId, String tableName, String columnName) {
        if (tableName == null || columnName == null) return null;
        // 通过 metadata_entity 查找 TABLE 类型获取 FQN 前缀
        String tableNameLower = tableName.toLowerCase();
        List<MetadataEntity> tables = entityService.search(tableNameLower, MetadataEntity.TYPE_TABLE, 1, 10);
        for (MetadataEntity t : tables) {
            if (t.getName().equalsIgnoreCase(tableNameLower)
                    || (t.getFqn() != null && t.getFqn().endsWith("." + tableNameLower))) {
                return t.getFqn() + "." + columnName.toLowerCase();
            }
        }
        return null;
    }

    /** 列派生关系记录 */
    private record ColumnDerivation(
            String targetTable, String targetColumn, String targetAlias,
            String sourceTable, String sourceColumn,
            String expression, String expressionType) {
    }

    /** 解析列派生关系 JSON */
    private List<ColumnDerivation> parseColumnDerivations(String columnDerivationsJson) {
        if (columnDerivationsJson == null || columnDerivationsJson.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(
                    columnDerivationsJson, new TypeReference<>() {});
            List<ColumnDerivation> derivations = new ArrayList<>();
            for (Map<String, Object> item : raw) {
                derivations.add(new ColumnDerivation(
                        valueAsString(item.get("targetTable")),
                        valueAsString(item.get("targetColumn")),
                        valueAsString(item.get("targetAlias")),
                        valueAsString(item.get("sourceTable")),
                        valueAsString(item.get("sourceColumn")),
                        valueAsString(item.get("expression")),
                        valueAsString(item.get("expressionType"))
                ));
            }
            return derivations;
        } catch (Exception e) {
            log.debug("解析 column_derivations 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 查找表名对应的 FQN 前缀（datasource.db） */
    private String findFqnPrefix(Long datasourceId, String tableName) {
        // 从 metadata_entity 中查找 TABLE 类型实体，匹配表名
        List<MetadataEntity> entities = entityService.search(tableName, MetadataEntity.TYPE_TABLE, 1, 5);
        for (MetadataEntity e : entities) {
            if (e.getName().equalsIgnoreCase(tableName)) {
                // FQN 格式: datasource.db.table → 去掉最后一段得到前缀
                String fqn = e.getFqn();
                int lastDot = fqn.lastIndexOf('.');
                return lastDot > 0 ? fqn.substring(0, lastDot) : null;
            }
        }
        return null;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    @Override
    public List<LineageTableVO> queryTableLineage(Long datasourceId, String tableName) {
        requireDatasourceAccess(datasourceId);
        List<QueryLineageTable> records = tableMapper.selectByTableAndDatasource(tableName, datasourceId, 100);
        Map<Long, String> questionMap = loadQuestionMap(datasourceId, records.stream()
                .map(QueryLineageTable::getQueryTaskId)
                .collect(Collectors.toSet()));
        return records.stream().map(r -> {
            LineageTableVO vo = new LineageTableVO();
            vo.setQueryTaskId(r.getQueryTaskId());
            vo.setSourceTable(r.getSourceTable());
            vo.setTargetName(r.getTargetName());
            vo.setRelationType(r.getRelationType());
            vo.setQuestion(questionMap.get(r.getQueryTaskId()));
            vo.setCreatedAt(r.getCreatedAt());
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public List<LineageColumnVO> queryColumnLineage(Long datasourceId, String tableName, String columnName) {
        requireDatasourceAccess(datasourceId);
        List<QueryLineageColumn> records = columnMapper.selectByColumnAndDatasource(tableName, columnName, datasourceId, 100);
        Map<Long, String> questionMap = loadQuestionMap(datasourceId, records.stream()
                .map(QueryLineageColumn::getQueryTaskId)
                .collect(Collectors.toSet()));
        return records.stream().map(r -> {
            LineageColumnVO vo = new LineageColumnVO();
            vo.setQueryTaskId(r.getQueryTaskId());
            vo.setSourceTable(r.getSourceTable());
            vo.setSourceColumn(r.getSourceColumn());
            vo.setExpression(r.getExpression());
            vo.setAliasName(r.getAliasName());
            vo.setQuestion(questionMap.get(r.getQueryTaskId()));
            vo.setCreatedAt(r.getCreatedAt());
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public ImpactAnalysisVO analyzeImpact(Long datasourceId, String tableName, String columnName) {
        requireDatasourceAccess(datasourceId);
        Long count = columnMapper.countByColumnAndDatasource(tableName, columnName, datasourceId);
        List<QueryLineageColumn> recent = columnMapper.selectByColumnAndDatasource(tableName, columnName, datasourceId, 10);
        ImpactAnalysisVO vo = new ImpactAnalysisVO();
        vo.setDependentQueryCount(count);
        vo.setRecentQueryTaskIds(recent.stream().map(QueryLineageColumn::getQueryTaskId).toList());
        vo.setTableName(tableName);
        vo.setColumnName(columnName);
        return vo;
    }

    private List<TableRef> parseTableRefs(String usedTables) throws Exception {
        Map<String, TableRef> tableRefs = new LinkedHashMap<>();
        if (usedTables == null || usedTables.isBlank()) {
            return List.of();
        }
        List<Object> items = objectMapper.readValue(usedTables, new TypeReference<>() {});
        for (Object item : items) {
            String tableName = null;
            String relationType = "FROM";
            if (item instanceof String text) {
                tableName = text;
            } else if (item instanceof Map<?, ?> map) {
                Object value = map.containsKey("table") ? map.get("table") : map.get("name");
                tableName = value == null ? null : String.valueOf(value);
                Object type = map.containsKey("relationType") ? map.get("relationType") : map.get("type");
                relationType = normalizeRelationType(valueAsString(type));
            }
            if (tableName != null && !tableName.isBlank()) {
                String normalized = tableName.replace("`", "").trim();
                tableRefs.putIfAbsent(normalized, new TableRef(normalized, relationType));
            }
        }
        return new ArrayList<>(tableRefs.values());
    }

    private List<ColumnRef> parseColumnRefs(String usedColumns, Set<String> tableNames) throws Exception {
        List<ColumnRef> columns = new ArrayList<>();
        if (usedColumns == null || usedColumns.isBlank()) {
            return columns;
        }
        List<Object> items = objectMapper.readValue(usedColumns, new TypeReference<>() {});
        String singleTable = tableNames.size() == 1 ? tableNames.iterator().next() : "";
        for (Object item : items) {
            ColumnRef ref = null;
            if (item instanceof String text) {
                ref = parseColumnText(text, singleTable);
            } else if (item instanceof Map<?, ?> map) {
                Object table = map.get("table");
                Object column = map.containsKey("column") ? map.get("column") : map.get("name");
                if (column != null) {
                    ref = new ColumnRef(
                            table == null ? singleTable : String.valueOf(table),
                            String.valueOf(column),
                            valueAsString(map.get("expression")),
                            valueAsString(map.get("alias"))
                    );
                }
            }
            if (ref != null && !ref.tableName().isBlank() && !ref.columnName().isBlank()) {
                columns.add(ref);
            }
        }
        return columns;
    }

    private ColumnRef parseColumnText(String text, String defaultTable) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.replace("`", "").trim();
        int dot = normalized.lastIndexOf('.');
        if (dot > 0 && dot < normalized.length() - 1) {
            return new ColumnRef(normalized.substring(0, dot), normalized.substring(dot + 1), normalized, null);
        }
        return new ColumnRef(defaultTable, normalized, normalized, null);
    }

    private String valueAsString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String normalizeRelationType(String relationType) {
        if (relationType == null || relationType.isBlank()) {
            return "FROM";
        }
        String normalized = relationType.trim().toUpperCase();
        return switch (normalized) {
            case "FROM", "JOIN", "SUBQUERY" -> normalized;
            default -> "FROM";
        };
    }

    private void requireDatasourceAccess(Long datasourceId) {
        if (datasourceId == null || !datasourceAccessService.checkAccess(datasourceId)) {
            throw new BusinessException(403, "无权查看该数据源血缘");
        }
    }

    private Map<Long, String> loadQuestionMap(Long datasourceId, Set<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>();
        List<QueryTask> tasks = queryTaskMapper.selectList(new LambdaQueryWrapper<QueryTask>()
                .in(QueryTask::getId, taskIds)
                .eq(QueryTask::getDatasourceId, datasourceId));
        for (QueryTask task : tasks) {
            result.put(task.getId(), task.getQuestion());
        }
        return result;
    }

    private record TableRef(String tableName, String relationType) {
    }

    private record ColumnRef(String tableName, String columnName, String expression, String aliasName) {
    }
}
