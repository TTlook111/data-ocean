package com.dataocean.module.query.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.pagination.PageRequest;
import com.dataocean.module.audit.service.AuditLogService;
import com.dataocean.module.audit.service.LineageService;
import com.dataocean.module.permission.entity.vo.PermissionContextVO;
import com.dataocean.module.permission.service.DataMaskingService;
import com.dataocean.module.permission.service.PermissionCalculator;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.entity.query.QueryHistoryQuery;
import com.dataocean.module.query.entity.vo.QueryTaskVO;
import com.dataocean.module.query.enums.QueryTaskStatus;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.query.service.QueryTaskService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 查询任务服务实现类。
 * <p>
 * 负责创建查询任务、更新结果、取消任务等操作。
 * 查询执行由 Python Agent 服务异步完成。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueryTaskServiceImpl implements QueryTaskService {

    private final QueryTaskMapper queryTaskMapper;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final LineageService lineageService;
    private final DataMaskingService dataMaskingService;
    private final PermissionCalculator permissionCalculator;

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public String submitQuery(Long userId, Long datasourceId, String question, Long conversationId) {
        String taskId = UUID.randomUUID().toString();
        log.info("提交查询任务 taskId={} userId={} datasourceId={}", taskId, userId, datasourceId);

        QueryTask task = QueryTask.builder()
                .taskId(taskId)
                .userId(userId)
                .datasourceId(datasourceId)
                .conversationId(conversationId)
                .question(question)
                .status(QueryTaskStatus.PROCESSING.name())
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        queryTaskMapper.insert(task);
        return taskId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public QueryTaskVO getTaskResult(String taskId, Long userId) {
        QueryTask task = findByTaskIdAndUser(taskId, userId);
        QueryTaskVO vo = toVO(task);

        // 权限控制：脱敏 + can_view_sql + can_export
        if (task.getDatasourceId() != null) {
            PermissionContextVO context = permissionCalculator.calculate(userId, task.getDatasourceId());

            // 对查询结果执行脱敏：优先使用 Python AST 标记的精确字段，fallback 到全量策略
            if (vo.getData() != null && !vo.getData().isEmpty()) {
                Map<String, String> maskedFieldsFromPython = parseMaskedFields(task.getMaskedFields());
                if (!maskedFieldsFromPython.isEmpty()) {
                    // Python 已精确标记本次 SQL 实际涉及的脱敏字段（输出列名 → 策略）
                    vo.setData(dataMaskingService.maskResultByFields(vo.getData(), maskedFieldsFromPython));
                } else if (context.getMaskColumns() != null && !context.getMaskColumns().isEmpty()) {
                    // fallback：按全量策略列名匹配
                    vo.setData(dataMaskingService.maskResult(vo.getData(), context.getMaskColumns()));
                }
            }

            // can_view_sql 控制：无权限则隐藏 SQL
            if (!context.isCanViewSql()) {
                vo.setSql(null);
            }

            // can_export 标志传给前端控制导出按钮
            vo.setCanExport(context.isCanExport());
        }
        return vo;
    }

    @Override
    public Page<QueryTaskVO> listHistory(Long userId, QueryHistoryQuery query) {
        Page<QueryTask> page = queryTaskMapper.selectPage(
                new Page<>(PageRequest.page(query.getPage()), PageRequest.size(query.getPageSize())),
                new LambdaQueryWrapper<QueryTask>()
                        .eq(QueryTask::getUserId, userId)
                        .eq(query.getDatasourceId() != null, QueryTask::getDatasourceId, query.getDatasourceId())
                        .eq(StringUtils.hasText(query.getStatus()), QueryTask::getStatus, query.getStatus())
                        .and(StringUtils.hasText(query.getKeyword()), wrapper -> wrapper
                                .like(QueryTask::getQuestion, query.getKeyword())
                                .or()
                                .like(QueryTask::getResultSql, query.getKeyword())
                                .or()
                                .like(QueryTask::getErrorMessage, query.getKeyword()))
                        .orderByDesc(QueryTask::getCreatedAt));
        Page<QueryTaskVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(this::toVO).toList());
        return result;
    }

    /**
     * 解析 Python 返回的 maskedFields JSON（格式：{"outputName": "STRATEGY", ...}）
     */
    private Map<String, String> parseMaskedFields(String maskedFieldsJson) {
        if (maskedFieldsJson == null || maskedFieldsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(maskedFieldsJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("解析 maskedFields 失败: {}", maskedFieldsJson);
            return Map.of();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void cancelTask(String taskId, Long userId) {
        log.info("取消查询任务 taskId={} userId={}", taskId, userId);
        QueryTask task = findByTaskIdAndUser(taskId, userId);
        if (!QueryTaskStatus.PROCESSING.name().equals(task.getStatus())) {
            throw new BusinessException("任务已完成，无法取消");
        }
        // 更新 Java 侧任务状态（Python 侧取消由 Controller 层协调）
        queryTaskMapper.update(null,
                new LambdaUpdateWrapper<QueryTask>()
                        .eq(QueryTask::getTaskId, taskId)
                        .eq(QueryTask::getUserId, userId)
                        .set(QueryTask::getStatus, QueryTaskStatus.CANCELLED.name())
                        .set(QueryTask::getCompletedAt, LocalDateTime.now()));
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public boolean updateTaskResult(String taskId, String resultJson) {
        log.info("更新查询任务结果 taskId={}", taskId);
        // 如果任务已被取消，不再覆盖状态
        QueryTask existing = findByTaskId(taskId);
        if (QueryTaskStatus.CANCELLED.name().equals(existing.getStatus())) {
            log.info("任务已取消，跳过结果回写 taskId={}", taskId);
            return false;
        }
        try {
            Map<String, Object> result = objectMapper.readValue(resultJson, new TypeReference<>() {});
            String status = (String) result.getOrDefault("status", "FAILED");
            LambdaUpdateWrapper<QueryTask> wrapper = new LambdaUpdateWrapper<QueryTask>()
                    .eq(QueryTask::getTaskId, taskId)
                    .eq(QueryTask::getStatus, QueryTaskStatus.PROCESSING.name())
                    .set(QueryTask::getStatus, status)
                    .set(QueryTask::getCompletedAt, LocalDateTime.now());

            if (result.containsKey("sql")) {
                wrapper.set(QueryTask::getResultSql, (String) result.get("sql"));
            }
            if (result.containsKey("sqlExplanation")) {
                wrapper.set(QueryTask::getSqlExplanation, (String) result.get("sqlExplanation"));
            }
            if (result.containsKey("rewrittenQuery")) {
                wrapper.set(QueryTask::getRewrittenQuery, (String) result.get("rewrittenQuery"));
            }
            if (result.containsKey("data")) {
                wrapper.set(QueryTask::getResultData, objectMapper.writeValueAsString(result.get("data")));
            }
            if (result.containsKey("columns")) {
                wrapper.set(QueryTask::getResultColumns, objectMapper.writeValueAsString(result.get("columns")));
            }
            if (result.containsKey("chartConfig")) {
                wrapper.set(QueryTask::getChartConfig, objectMapper.writeValueAsString(result.get("chartConfig")));
            }
            if (result.containsKey("usedTables")) {
                wrapper.set(QueryTask::getUsedTables, objectMapper.writeValueAsString(result.get("usedTables")));
            }
            if (result.containsKey("usedColumns")) {
                wrapper.set(QueryTask::getUsedColumns, objectMapper.writeValueAsString(result.get("usedColumns")));
            }
            if (result.containsKey("maskedFields")) {
                wrapper.set(QueryTask::getMaskedFields, objectMapper.writeValueAsString(result.get("maskedFields")));
            }
            if (result.containsKey("promptVersions")) {
                wrapper.set(QueryTask::getPromptVersions, objectMapper.writeValueAsString(result.get("promptVersions")));
            }
            if (result.containsKey("degraded")) {
                wrapper.set(QueryTask::getDegraded, Boolean.TRUE.equals(result.get("degraded")));
            }
            if (result.containsKey("degradeNotice")) {
                wrapper.set(QueryTask::getDegradeNotice, (String) result.get("degradeNotice"));
            }
            if (result.containsKey("error")) {
                wrapper.set(QueryTask::getErrorMessage, (String) result.get("error"));
            }
            if (result.containsKey("retryCount")) {
                wrapper.set(QueryTask::getRetryCount, ((Number) result.get("retryCount")).intValue());
            }
            if (result.containsKey("totalTimeMs")) {
                wrapper.set(QueryTask::getTotalTimeMs, ((Number) result.get("totalTimeMs")).intValue());
            }

            int rows = queryTaskMapper.update(null, wrapper);
            if (rows == 0) {
                log.info("任务状态已变更（可能已取消），跳过结果回写 taskId={}", taskId);
                return false;
            }

            // 异步写入审计日志和血缘数据（延迟到事务提交后执行，确保读到已提交数据）
            final Long taskDbId = existing.getId();
            final String finalUsedTablesJson = result.containsKey("usedTables")
                    ? objectMapper.writeValueAsString(result.get("usedTables")) : null;
            final String finalUsedColumnsJson = result.containsKey("usedColumns")
                    ? objectMapper.writeValueAsString(result.get("usedColumns")) : null;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    auditLogService.recordAudit(taskDbId);
                    lineageService.saveLineage(taskDbId, finalUsedTablesJson, finalUsedColumnsJson);
                }
            });

            return true;
        } catch (Exception e) {
            log.error("更新查询任务结果失败 taskId={}", taskId, e);
            queryTaskMapper.update(null,
                    new LambdaUpdateWrapper<QueryTask>()
                            .eq(QueryTask::getTaskId, taskId)
                            .set(QueryTask::getStatus, QueryTaskStatus.FAILED.name())
                            .set(QueryTask::getErrorMessage, "结果解析失败：" + e.getMessage())
                            .set(QueryTask::getCompletedAt, LocalDateTime.now()));
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateTaskProgress(String taskId, String node, String message) {
        // 仅更新仍在处理中的任务，避免覆盖已取消/已完成的终态
        queryTaskMapper.update(null,
                new LambdaUpdateWrapper<QueryTask>()
                        .eq(QueryTask::getTaskId, taskId)
                        .eq(QueryTask::getStatus, QueryTaskStatus.PROCESSING.name())
                        .set(QueryTask::getProgressNode, node)
                        .set(QueryTask::getProgressMessage, message));
    }

    /**
     * 根据 taskId 查询任务，不存在则抛出业务异常。
     */
    private QueryTask findByTaskId(String taskId) {
        QueryTask task = queryTaskMapper.selectOne(
                new LambdaQueryWrapper<QueryTask>().eq(QueryTask::getTaskId, taskId));
        if (task == null) {
            throw new BusinessException("查询任务不存在");
        }
        return task;
    }

    /**
     * 根据 taskId 和 userId 查询任务，校验归属后返回。
     */
    private QueryTask findByTaskIdAndUser(String taskId, Long userId) {
        QueryTask task = queryTaskMapper.selectOne(
                new LambdaQueryWrapper<QueryTask>()
                        .eq(QueryTask::getTaskId, taskId)
                        .eq(QueryTask::getUserId, userId));
        if (task == null) {
            throw new BusinessException("查询任务不存在或无权访问");
        }
        return task;
    }

    @Override
    public Long getTaskDbId(String taskId, Long userId) {
        QueryTask task = findByTaskIdAndUser(taskId, userId);
        return task.getId();
    }

    /**
     * 将查询任务实体转换为前端展示 VO，反序列化 JSON 字段。
     */
    @SuppressWarnings("unchecked")
    private QueryTaskVO toVO(QueryTask task) {
        try {
            List<Map<String, Object>> data = task.getResultData() != null
                    ? objectMapper.readValue(task.getResultData(), new TypeReference<>() {}) : null;
            List<Map<String, String>> columns = task.getResultColumns() != null
                    ? objectMapper.readValue(task.getResultColumns(), new TypeReference<>() {}) : null;
            Map<String, Object> chartConfig = task.getChartConfig() != null
                    ? objectMapper.readValue(task.getChartConfig(), new TypeReference<>() {}) : null;
            List<String> usedTables = task.getUsedTables() != null
                    ? objectMapper.readValue(task.getUsedTables(), new TypeReference<>() {}) : null;
            List<String> usedColumns = task.getUsedColumns() != null
                    ? objectMapper.readValue(task.getUsedColumns(), new TypeReference<>() {}) : null;
            List<Map<String, Object>> promptVersions = task.getPromptVersions() != null
                    ? objectMapper.readValue(task.getPromptVersions(), new TypeReference<>() {}) : null;

            return QueryTaskVO.builder()
                    .taskId(task.getTaskId())
                    .status(task.getStatus())
                    .progressNode(task.getProgressNode())
                    .progressMessage(task.getProgressMessage())
                    .question(task.getQuestion())
                    .rewrittenQuery(task.getRewrittenQuery())
                    .sql(task.getResultSql())
                    .sqlExplanation(task.getSqlExplanation())
                    .data(data)
                    .columns(columns)
                    .rowCount(data != null ? data.size() : 0)
                    .chartConfig(chartConfig)
                    .usedTables(usedTables)
                    .usedColumns(usedColumns)
                    .promptVersions(promptVersions)
                    .degraded(task.getDegraded())
                    .degradeNotice(task.getDegradeNotice())
                    .errorMessage(task.getErrorMessage())
                    .retryCount(task.getRetryCount())
                    .totalTimeMs(task.getTotalTimeMs())
                    .createdAt(task.getCreatedAt())
                    .completedAt(task.getCompletedAt())
                    .build();
        } catch (Exception e) {
            log.error("查询任务 VO 转换失败 taskId={}", task.getTaskId(), e);
            return QueryTaskVO.builder()
                    .taskId(task.getTaskId())
                    .status(task.getStatus())
                    .progressNode(task.getProgressNode())
                    .progressMessage(task.getProgressMessage())
                    .question(task.getQuestion())
                    .errorMessage(task.getErrorMessage())
                    .createdAt(task.getCreatedAt())
                    .build();
        }
    }
}
