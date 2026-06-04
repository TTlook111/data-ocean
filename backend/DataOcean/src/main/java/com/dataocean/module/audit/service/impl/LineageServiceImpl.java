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
    private final ObjectMapper objectMapper;

    @Override
    @Async
    public void saveLineage(Long queryTaskId, String usedTables, String usedColumns) {
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
        } catch (Exception e) {
            log.error("lineage save failed queryTaskId={}", queryTaskId, e);
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
