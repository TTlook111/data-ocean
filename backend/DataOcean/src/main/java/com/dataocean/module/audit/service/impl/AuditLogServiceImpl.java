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
        LambdaQueryWrapper<QueryAuditLog> wrapper = new LambdaQueryWrapper<>();
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
        LambdaQueryWrapper<QueryAuditLog> wrapper = new LambdaQueryWrapper<QueryAuditLog>()
                .eq(QueryAuditLog::getIsSlow, true)
                .orderByDesc(QueryAuditLog::getExecutionTimeMs);
        Page<QueryAuditLog> result = auditLogMapper.selectPage(new Page<>(page, pageSize), wrapper);
        Page<AuditLogVO> voPage = new Page<>(page, pageSize, result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    @Override
    public AuditStatsVO getStats(Long datasourceId, int days) {
        LocalDateTime startTime = LocalDateTime.now().minusDays(days);
        LambdaQueryWrapper<QueryAuditLog> baseWrapper = new LambdaQueryWrapper<QueryAuditLog>()
                .ge(QueryAuditLog::getCreatedAt, startTime);
        if (datasourceId != null) {
            baseWrapper.eq(QueryAuditLog::getDatasourceId, datasourceId);
        }
        // 使用数据库聚合查询，避免全量加载到内存
        Long totalQueries = auditLogMapper.selectCount(baseWrapper);

        LambdaQueryWrapper<QueryAuditLog> successWrapper = new LambdaQueryWrapper<QueryAuditLog>()
                .ge(QueryAuditLog::getCreatedAt, startTime)
                .eq(QueryAuditLog::getIsSuccess, true);
        if (datasourceId != null) {
            successWrapper.eq(QueryAuditLog::getDatasourceId, datasourceId);
        }
        Long successCount = auditLogMapper.selectCount(successWrapper);

        LambdaQueryWrapper<QueryAuditLog> slowWrapper = new LambdaQueryWrapper<QueryAuditLog>()
                .ge(QueryAuditLog::getCreatedAt, startTime)
                .eq(QueryAuditLog::getIsSlow, true);
        if (datasourceId != null) {
            slowWrapper.eq(QueryAuditLog::getDatasourceId, datasourceId);
        }
        Long slowCount = auditLogMapper.selectCount(slowWrapper);

        // 平均耗时：取有执行时间的记录计算
        LambdaQueryWrapper<QueryAuditLog> avgWrapper = new LambdaQueryWrapper<QueryAuditLog>()
                .select(QueryAuditLog::getExecutionTimeMs)
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

    @Override
    public void promoteToTemplate(Long auditLogId) {
        QueryAuditLog auditLog = auditLogMapper.selectById(auditLogId);
        if (auditLog == null) {
            throw new BusinessException(404, "审计日志不存在");
        }
        if (!Boolean.TRUE.equals(auditLog.getIsSuccess())) {
            throw new BusinessException("只能将成功的查询提升为模板");
        }
        // 模板提升逻辑预留：后续对接模板表
        log.info("查询已提升为模板 auditLogId={} question={}", auditLogId, auditLog.getQuestion());
    }

    private AuditLogVO toVO(QueryAuditLog entity) {
        AuditLogVO vo = new AuditLogVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
