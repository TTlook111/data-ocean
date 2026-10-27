package com.dataocean.module.audit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.audit.entity.po.QueryLineageColumn;
import com.dataocean.module.audit.entity.po.QueryLineageTable;
import com.dataocean.module.audit.entity.vo.ImpactAnalysisVO;
import com.dataocean.module.audit.entity.vo.LineageColumnVO;
import com.dataocean.module.audit.entity.vo.LineageTableVO;
import com.dataocean.module.audit.mapper.QueryLineageColumnMapper;
import com.dataocean.module.audit.mapper.QueryLineageTableMapper;
import com.dataocean.module.audit.service.LineageService;
import com.dataocean.module.query.entity.po.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void saveProtectedLineage(QueryTask task, List<Map<String, Object>> sourceTrace) {
        try {
            Set<String> tableNames = new LinkedHashSet<>(objectMapper.readValue(task.getUsedTables(), new TypeReference<List<String>>() {}));
            Set<String> fields = new LinkedHashSet<>(objectMapper.readValue(task.getUsedColumns(), new TypeReference<List<String>>() {}));
            for (String table : tableNames) {
                QueryLineageTable row = new QueryLineageTable();
                row.setQueryTaskId(task.getId()); row.setSourceTable(table); row.setTargetName("query:" + task.getTaskId());
                row.setRelationType("USAGE"); row.setCreatedAt(task.getCompletedAt()); tableMapper.insert(row);
            }
            Set<String> keys = new LinkedHashSet<>();
            for (String field : fields) saveProtectedColumn(task, tableNames, keys, field, null);
            for (Map<String, Object> entry : sourceTrace) {
                Object sources = entry.get("sources");
                if (!(sources instanceof List<?> values)) throw new IllegalArgumentException();
                for (Object source : values) {
                    if (!(source instanceof String field) || !fields.contains(field)) throw new IllegalArgumentException();
                    saveProtectedColumn(task, tableNames, keys, field, String.valueOf(entry.get("outputColumn")));
                }
            }
        } catch (Exception exception) { throw new com.dataocean.common.exception.BusinessException("受保护查询来源索引写入失败"); }
    }

    private void saveProtectedColumn(QueryTask task, Set<String> tables, Set<String> keys, String field, String output) {
        String[] parts = field.split("\\.", -1);
        if (parts.length != 2 || parts[1].isBlank() || !tables.contains(parts[0])) throw new IllegalArgumentException();
        if (!keys.add(field + "|" + java.util.Objects.toString(output, ""))) return;
        QueryLineageColumn row = new QueryLineageColumn();
        row.setQueryTaskId(task.getId()); row.setSourceTable(parts[0]); row.setSourceColumn(parts[1]);
        row.setAliasName(output); row.setExpression(output == null ? "查询使用（含筛选、连接、分组及排序）" : "结果列来源（转换口径见受保护 SQL）");
        row.setCreatedAt(task.getCompletedAt()); columnMapper.insert(row);
    }

    @Override
    public List<LineageTableVO> queryTableLineage(Long datasourceId, String tableName) {
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
        ImpactAnalysisVO vo = new ImpactAnalysisVO();
        boolean tableOnly = columnName == null || columnName.isBlank();
        vo.setDependentQueryCount(tableOnly ? tableMapper.countByTableAndDatasource(tableName, datasourceId)
                : columnMapper.countByColumnAndDatasource(tableName, columnName, datasourceId));
        vo.setRecentQueryTaskIds(tableOnly ? queryTableLineage(datasourceId, tableName).stream()
                .map(LineageTableVO::getQueryTaskId).distinct().limit(10).toList()
                : columnMapper.recentTasks(tableName, columnName, datasourceId));
        vo.setTableName(tableName); vo.setColumnName(columnName);
        vo.setCoverageStartAt(tableMapper.coverageStart(datasourceId));
        vo.setPendingDatasourceQueryCount(queryTaskMapper.selectCount(new LambdaQueryWrapper<QueryTask>()
                .eq(QueryTask::getDatasourceId, datasourceId).eq(QueryTask::getStatus, "COMPLETED")
                .eq(QueryTask::getTraceProjectionStatus, "PENDING")));
        vo.setCoverageNote("仅统计已记录的成功受保护 S1 查询，覆盖历史快照；未记录不代表没有影响。明细最多 100 条，总数按任务去重。");
        return vo;
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


}
