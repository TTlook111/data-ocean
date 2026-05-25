package com.dataocean.module.query.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.query.entity.QueryTask;
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
        return toVO(task);
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
    public void updateTaskResult(String taskId, String resultJson) {
        log.info("更新查询任务结果 taskId={}", taskId);
        // 如果任务已被取消，不再覆盖状态
        QueryTask existing = findByTaskId(taskId);
        if (QueryTaskStatus.CANCELLED.name().equals(existing.getStatus())) {
            log.info("任务已取消，跳过结果回写 taskId={}", taskId);
            return;
        }
        try {
            Map<String, Object> result = objectMapper.readValue(resultJson, new TypeReference<>() {});
            String status = (String) result.getOrDefault("status", "FAILED");
            LambdaUpdateWrapper<QueryTask> wrapper = new LambdaUpdateWrapper<QueryTask>()
                    .eq(QueryTask::getTaskId, taskId)
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
            if (result.containsKey("error")) {
                wrapper.set(QueryTask::getErrorMessage, (String) result.get("error"));
            }
            if (result.containsKey("retryCount")) {
                wrapper.set(QueryTask::getRetryCount, ((Number) result.get("retryCount")).intValue());
            }
            if (result.containsKey("totalTimeMs")) {
                wrapper.set(QueryTask::getTotalTimeMs, ((Number) result.get("totalTimeMs")).intValue());
            }

            queryTaskMapper.update(null, wrapper);
        } catch (Exception e) {
            log.error("更新查询任务结果失败 taskId={}", taskId, e);
            queryTaskMapper.update(null,
                    new LambdaUpdateWrapper<QueryTask>()
                            .eq(QueryTask::getTaskId, taskId)
                            .set(QueryTask::getStatus, QueryTaskStatus.FAILED.name())
                            .set(QueryTask::getErrorMessage, "结果解析失败：" + e.getMessage())
                            .set(QueryTask::getCompletedAt, LocalDateTime.now()));
        }
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

            return QueryTaskVO.builder()
                    .taskId(task.getTaskId())
                    .status(task.getStatus())
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
                    .question(task.getQuestion())
                    .errorMessage(task.getErrorMessage())
                    .createdAt(task.getCreatedAt())
                    .build();
        }
    }
}
