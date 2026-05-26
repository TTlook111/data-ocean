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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LineageServiceImpl implements LineageService {

    private final QueryLineageTableMapper tableMapper;
    private final QueryLineageColumnMapper columnMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Async
    public void saveLineage(Long queryTaskId, String usedTables, String usedColumns) {
        try {
            // 解析并批量保存表级血缘
            if (usedTables != null && !usedTables.isBlank()) {
                List<Map<String, String>> tables = objectMapper.readValue(usedTables, new TypeReference<>() {});
                List<QueryLineageTable> batch = new ArrayList<>(tables.size());
                for (Map<String, String> table : tables) {
                    QueryLineageTable lineage = new QueryLineageTable();
                    lineage.setQueryTaskId(queryTaskId);
                    lineage.setSourceTable(table.getOrDefault("table", table.getOrDefault("name", "")));
                    lineage.setRelationType(table.getOrDefault("type", "FROM"));
                    lineage.setCreatedAt(LocalDateTime.now());
                    batch.add(lineage);
                }
                if (!batch.isEmpty()) {
                    tableMapper.insert(batch);
                }
            }
            // 解析并批量保存字段级血缘
            if (usedColumns != null && !usedColumns.isBlank()) {
                List<Map<String, String>> columns = objectMapper.readValue(usedColumns, new TypeReference<>() {});
                List<QueryLineageColumn> batch = new ArrayList<>(columns.size());
                for (Map<String, String> col : columns) {
                    QueryLineageColumn lineage = new QueryLineageColumn();
                    lineage.setQueryTaskId(queryTaskId);
                    lineage.setSourceTable(col.getOrDefault("table", ""));
                    lineage.setSourceColumn(col.getOrDefault("column", col.getOrDefault("name", "")));
                    lineage.setExpression(col.get("expression"));
                    lineage.setAliasName(col.get("alias"));
                    lineage.setCreatedAt(LocalDateTime.now());
                    batch.add(lineage);
                }
                if (!batch.isEmpty()) {
                    columnMapper.insert(batch);
                }
            }
            log.debug("血缘数据保存成功 queryTaskId={}", queryTaskId);
        } catch (Exception e) {
            log.error("血缘数据保存失败 queryTaskId={}", queryTaskId, e);
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
        return records.stream().map(r -> {
            LineageTableVO vo = new LineageTableVO();
            vo.setQueryTaskId(r.getQueryTaskId());
            vo.setSourceTable(r.getSourceTable());
            vo.setTargetName(r.getTargetName());
            vo.setRelationType(r.getRelationType());
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
        return records.stream().map(r -> {
            LineageColumnVO vo = new LineageColumnVO();
            vo.setQueryTaskId(r.getQueryTaskId());
            vo.setSourceTable(r.getSourceTable());
            vo.setSourceColumn(r.getSourceColumn());
            vo.setExpression(r.getExpression());
            vo.setAliasName(r.getAliasName());
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
}
