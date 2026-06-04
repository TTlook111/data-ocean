package com.dataocean.module.audit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.audit.entity.QueryLineageColumn;
import com.dataocean.module.audit.entity.QueryLineageTable;
import com.dataocean.module.audit.entity.vo.ImpactAnalysisVO;
import com.dataocean.module.audit.entity.vo.LineageColumnVO;
import com.dataocean.module.audit.entity.vo.LineageTableVO;
import com.dataocean.module.audit.mapper.QueryLineageColumnMapper;
import com.dataocean.module.audit.mapper.QueryLineageTableMapper;
import com.dataocean.module.audit.service.LineageService;
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
    private final ObjectMapper objectMapper;

    @Override
    @Async
    public void saveLineage(Long queryTaskId, String usedTables, String usedColumns) {
        try {
            Set<String> tableNames = parseTableNames(usedTables);
            if (!tableNames.isEmpty()) {
                List<QueryLineageTable> batch = new ArrayList<>(tableNames.size());
                for (String tableName : tableNames) {
                    QueryLineageTable lineage = new QueryLineageTable();
                    lineage.setQueryTaskId(queryTaskId);
                    lineage.setSourceTable(tableName);
                    lineage.setRelationType("FROM");
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
    public List<LineageTableVO> queryTableLineage(String tableName) {
        List<QueryLineageTable> records = tableMapper.selectList(
                new LambdaQueryWrapper<QueryLineageTable>()
                        .eq(QueryLineageTable::getSourceTable, tableName)
                        .orderByDesc(QueryLineageTable::getCreatedAt)
                        .last("LIMIT 100")
        );
        Map<Long, String> questionMap = loadQuestionMap(records.stream()
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
    public List<LineageColumnVO> queryColumnLineage(String tableName, String columnName) {
        List<QueryLineageColumn> records = columnMapper.selectList(
                new LambdaQueryWrapper<QueryLineageColumn>()
                        .eq(QueryLineageColumn::getSourceTable, tableName)
                        .eq(QueryLineageColumn::getSourceColumn, columnName)
                        .orderByDesc(QueryLineageColumn::getCreatedAt)
                        .last("LIMIT 100")
        );
        Map<Long, String> questionMap = loadQuestionMap(records.stream()
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
    public ImpactAnalysisVO analyzeImpact(String tableName, String columnName) {
        Long count = columnMapper.selectCount(
                new LambdaQueryWrapper<QueryLineageColumn>()
                        .eq(QueryLineageColumn::getSourceTable, tableName)
                        .eq(QueryLineageColumn::getSourceColumn, columnName)
        );
        List<Object> taskIds = columnMapper.selectObjs(
                new LambdaQueryWrapper<QueryLineageColumn>()
                        .select(QueryLineageColumn::getQueryTaskId)
                        .eq(QueryLineageColumn::getSourceTable, tableName)
                        .eq(QueryLineageColumn::getSourceColumn, columnName)
                        .orderByDesc(QueryLineageColumn::getCreatedAt)
                        .last("LIMIT 10")
        );
        ImpactAnalysisVO vo = new ImpactAnalysisVO();
        vo.setDependentQueryCount(count);
        vo.setRecentQueryTaskIds(taskIds.stream().map(o -> ((Number) o).longValue()).toList());
        vo.setTableName(tableName);
        vo.setColumnName(columnName);
        return vo;
    }

    private Set<String> parseTableNames(String usedTables) throws Exception {
        Set<String> tableNames = new LinkedHashSet<>();
        if (usedTables == null || usedTables.isBlank()) {
            return tableNames;
        }
        List<Object> items = objectMapper.readValue(usedTables, new TypeReference<>() {});
        for (Object item : items) {
            String tableName = null;
            if (item instanceof String text) {
                tableName = text;
            } else if (item instanceof Map<?, ?> map) {
                Object value = map.containsKey("table") ? map.get("table") : map.get("name");
                tableName = value == null ? null : String.valueOf(value);
            }
            if (tableName != null && !tableName.isBlank()) {
                tableNames.add(tableName.replace("`", "").trim());
            }
        }
        return tableNames;
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

    private Map<Long, String> loadQuestionMap(Set<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>();
        for (QueryTask task : queryTaskMapper.selectBatchIds(taskIds)) {
            result.put(task.getId(), task.getQuestion());
        }
        return result;
    }

    private record ColumnRef(String tableName, String columnName, String expression, String aliasName) {
    }
}
