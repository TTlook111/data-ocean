package com.dataocean.module.audit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.audit.entity.QueryAuditLog;
import com.dataocean.module.audit.entity.dto.AuditLogQueryDTO;
import com.dataocean.module.audit.entity.vo.AuditLogVO;
import com.dataocean.module.audit.entity.vo.AuditStatsVO;
import com.dataocean.module.audit.mapper.QueryAuditLogMapper;
import com.dataocean.module.audit.service.AuditLogService;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogServiceImpl implements AuditLogService {

    private final QueryAuditLogMapper auditLogMapper;
    private final QueryTaskMapper queryTaskMapper;

    @Value("${dataocean.audit.slow-query-threshold-ms:5000}")
    private int slowQueryThresholdMs;

    /**
     * 异步记录审计日志。
     * 注意：此方法依赖 Spring AOP 代理实现异步，必须从外部 Bean 调用，
     * 不可在本类内部通过 this 调用，否则 @Async 不生效。
     */
    @Override
    @Async
    public void recordAudit(Long queryTaskId) {
        try {
            QueryTask task = queryTaskMapper.selectById(queryTaskId);
            if (task == null) {
                log.warn("审计记录失败：查询任务不存在 queryTaskId={}", queryTaskId);
                return;
            }
            QueryAuditLog auditLog = new QueryAuditLog();
            auditLog.setQueryTaskId(queryTaskId);
            auditLog.setUserId(task.getUserId());
            auditLog.setDatasourceId(task.getDatasourceId());
            auditLog.setQuestion(task.getQuestion());
            auditLog.setSqlText(task.getResultSql());
            auditLog.setUsedTables(task.getUsedTables());
            auditLog.setUsedFields(task.getUsedColumns());
            auditLog.setPromptVersions(task.getPromptVersions());
            auditLog.setExecutionTimeMs(task.getTotalTimeMs());
            auditLog.setIsSuccess("COMPLETED".equals(task.getStatus()));
            auditLog.setErrorMessage(task.getErrorMessage());
            auditLog.setIsSlow(task.getTotalTimeMs() != null && task.getTotalTimeMs() > slowQueryThresholdMs);
            auditLog.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(auditLog);
            log.debug("审计日志写入成功 queryTaskId={}", queryTaskId);
        } catch (Exception e) {
            log.error("审计日志写入失败 queryTaskId={}", queryTaskId, e);
        }
    }

    @Override
    public void updateFeedback(Long queryTaskId, String feedbackType) {
        QueryAuditLog auditLog = auditLogMapper.selectOne(
                new LambdaQueryWrapper<QueryAuditLog>()
                        .eq(QueryAuditLog::getQueryTaskId, queryTaskId)
        );
        if (auditLog != null) {
            auditLog.setUserFeedback(feedbackType);
            auditLogMapper.updateById(auditLog);
        }
    }

    @Override
    public Page<AuditLogVO> listAuditLogs(AuditLogQueryDTO query) {
        return queryAuditLogs(query, null);
    }

    @Override
    public Page<AuditLogVO> listAuditLogsInDatasources(AuditLogQueryDTO query,
                                                       java.util.Collection<Long> visibleDatasourceIds) {
        // 显式筛选一个自己无权的数据源时直接拒绝，而不是返回空页：
        // 空页会把「无权」伪装成「该数据源没有审计记录」。
        if (query.getDatasourceId() != null
                && (visibleDatasourceIds == null || !visibleDatasourceIds.contains(query.getDatasourceId()))) {
            throw new BusinessException(403, "没有负责该数据源，无法查看其审计记录");
        }
        if (visibleDatasourceIds == null || visibleDatasourceIds.isEmpty()) {
            return new Page<>(query.getPageNo(), query.getPageSize(), 0);
        }
        return queryAuditLogs(query, visibleDatasourceIds);
    }

    /** `scopedDatasourceIds == null` 表示不按数据源限制（仅供既有内部调用）。 */
    private Page<AuditLogVO> queryAuditLogs(AuditLogQueryDTO query,
                                            java.util.Collection<Long> scopedDatasourceIds) {
        LambdaQueryWrapper<QueryAuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(scopedDatasourceIds != null, QueryAuditLog::getDatasourceId, scopedDatasourceIds);
        if (query.getUserId() != null) {
            wrapper.eq(QueryAuditLog::getUserId, query.getUserId());
        }
        if (query.getDatasourceId() != null) {
            wrapper.eq(QueryAuditLog::getDatasourceId, query.getDatasourceId());
        }
        if (query.getIsSuccess() != null) {
            wrapper.eq(QueryAuditLog::getIsSuccess, query.getIsSuccess());
        }
        if (query.getIsSlow() != null) {
            wrapper.eq(QueryAuditLog::getIsSlow, query.getIsSlow());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(QueryAuditLog::getQuestion, query.getKeyword());
        }
        if (StringUtils.hasText(query.getStartTime())) {
            wrapper.ge(QueryAuditLog::getCreatedAt, LocalDateTime.parse(query.getStartTime(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
        if (StringUtils.hasText(query.getEndTime())) {
            wrapper.le(QueryAuditLog::getCreatedAt, LocalDateTime.parse(query.getEndTime(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
        wrapper.orderByDesc(QueryAuditLog::getCreatedAt);

        Page<QueryAuditLog> page = auditLogMapper.selectPage(
                new Page<>(query.getPageNo(), query.getPageSize()), wrapper);

        Page<AuditLogVO> result = new Page<>(query.getPageNo(), query.getPageSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(this::toVO).toList());
        return result;
    }

    @Override
    public AuditLogVO getAuditLogDetail(Long id) {
        QueryAuditLog log = auditLogMapper.selectById(id);
        if (log == null) {
            throw new BusinessException(404, "审计日志不存在");
        }
        return toVO(log);
    }

    @Override
    public Page<AuditLogVO> listSlowQueries(int page, int pageSize) {
        return querySlowQueries(page, pageSize, null);
    }

    @Override
    public Page<AuditLogVO> listSlowQueriesInDatasources(int page, int pageSize,
                                                         java.util.Collection<Long> visibleDatasourceIds) {
        if (visibleDatasourceIds == null || visibleDatasourceIds.isEmpty()) {
            return new Page<>(page, pageSize, 0);
        }
        return querySlowQueries(page, pageSize, visibleDatasourceIds);
    }

    private Page<AuditLogVO> querySlowQueries(int page, int pageSize,
                                              java.util.Collection<Long> scopedDatasourceIds) {
        LambdaQueryWrapper<QueryAuditLog> wrapper = new LambdaQueryWrapper<QueryAuditLog>()
                .in(scopedDatasourceIds != null, QueryAuditLog::getDatasourceId, scopedDatasourceIds)
                .eq(QueryAuditLog::getIsSlow, true)
                .orderByDesc(QueryAuditLog::getExecutionTimeMs);
        Page<QueryAuditLog> result = auditLogMapper.selectPage(new Page<>(page, pageSize), wrapper);
        Page<AuditLogVO> voPage = new Page<>(page, pageSize, result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    @Override
    public AuditStatsVO getStats(Long datasourceId, int days) {
        return queryStats(datasourceId, days, null);
    }

    @Override
    public AuditStatsVO getStatsInDatasources(Long datasourceId, int days,
                                              java.util.Collection<Long> visibleDatasourceIds) {
        if (datasourceId != null
                && (visibleDatasourceIds == null || !visibleDatasourceIds.contains(datasourceId))) {
            throw new BusinessException(403, "没有负责该数据源，无法查看其审计统计");
        }
        if (visibleDatasourceIds == null || visibleDatasourceIds.isEmpty()) {
            // 空负责源返回全零统计，不能退化成全局统计
            AuditStatsVO empty = new AuditStatsVO();
            empty.setTotalQueries(0L);
            empty.setSuccessCount(0L);
            empty.setSlowQueryCount(0L);
            empty.setSuccessRate(0.0);
            empty.setSlowQueryRate(0.0);
            empty.setAvgExecutionTimeMs(0.0);
            return empty;
        }
        return queryStats(datasourceId, days, visibleDatasourceIds);
    }

    private AuditStatsVO queryStats(Long datasourceId, int days,
                                    java.util.Collection<Long> scopedDatasourceIds) {
        LocalDateTime startTime = LocalDateTime.now().minusDays(days);
        LambdaQueryWrapper<QueryAuditLog> baseWrapper = new LambdaQueryWrapper<QueryAuditLog>()
                .in(scopedDatasourceIds != null, QueryAuditLog::getDatasourceId, scopedDatasourceIds)
                .ge(QueryAuditLog::getCreatedAt, startTime);
        if (datasourceId != null) {
            baseWrapper.eq(QueryAuditLog::getDatasourceId, datasourceId);
        }
        // 使用数据库聚合查询，避免全量加载到内存
        Long totalQueries = auditLogMapper.selectCount(baseWrapper);

        LambdaQueryWrapper<QueryAuditLog> successWrapper = new LambdaQueryWrapper<QueryAuditLog>()
                .in(scopedDatasourceIds != null, QueryAuditLog::getDatasourceId, scopedDatasourceIds)
                .ge(QueryAuditLog::getCreatedAt, startTime)
                .eq(QueryAuditLog::getIsSuccess, true);
        if (datasourceId != null) {
            successWrapper.eq(QueryAuditLog::getDatasourceId, datasourceId);
        }
        Long successCount = auditLogMapper.selectCount(successWrapper);

        LambdaQueryWrapper<QueryAuditLog> slowWrapper = new LambdaQueryWrapper<QueryAuditLog>()
                .in(scopedDatasourceIds != null, QueryAuditLog::getDatasourceId, scopedDatasourceIds)
                .ge(QueryAuditLog::getCreatedAt, startTime)
                .eq(QueryAuditLog::getIsSlow, true);
        if (datasourceId != null) {
            slowWrapper.eq(QueryAuditLog::getDatasourceId, datasourceId);
        }
        Long slowCount = auditLogMapper.selectCount(slowWrapper);

        // 平均耗时：取有执行时间的记录计算
        LambdaQueryWrapper<QueryAuditLog> avgWrapper = new LambdaQueryWrapper<QueryAuditLog>()
                .select(QueryAuditLog::getExecutionTimeMs)
                .in(scopedDatasourceIds != null, QueryAuditLog::getDatasourceId, scopedDatasourceIds)
                .ge(QueryAuditLog::getCreatedAt, startTime)
                .isNotNull(QueryAuditLog::getExecutionTimeMs);
        if (datasourceId != null) {
            avgWrapper.eq(QueryAuditLog::getDatasourceId, datasourceId);
        }
        List<Object> timeMsList = auditLogMapper.selectObjs(avgWrapper);
        double avgTimeMs = timeMsList.stream()
                .mapToInt(o -> ((Number) o).intValue())
                .average().orElse(0.0);

        AuditStatsVO stats = new AuditStatsVO();
        stats.setTotalQueries(totalQueries);
        stats.setSuccessCount(successCount);
        stats.setSuccessRate(totalQueries == 0 ? 0.0 : (double) successCount / totalQueries * 100);
        stats.setAvgExecutionTimeMs(avgTimeMs);
        stats.setSlowQueryCount(slowCount);
        stats.setSlowQueryRate(totalQueries == 0 ? 0.0 : (double) slowCount / totalQueries * 100);
        return stats;
    }

    private AuditLogVO toVO(QueryAuditLog entity) {
        AuditLogVO vo = new AuditLogVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
