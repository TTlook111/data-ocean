package com.dataocean.module.audit.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.audit.service.AuditLogService;
import com.dataocean.module.audit.service.LineageService;
import com.dataocean.module.query.entity.po.QueryAttempt;
import com.dataocean.module.query.entity.po.QueryTask;
import com.dataocean.module.query.mapper.QueryAttemptMapper;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 任务行锁串行化投影；索引、审计和完成标记同事务，崩溃后可安全重试。 */
@Service
@RequiredArgsConstructor
public class QueryTraceProjectionService {
    private final QueryTaskMapper tasks;
    private final QueryAttemptMapper attempts;
    private final AuditLogService audit;
    private final LineageService lineage;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void project(String taskId) {
        QueryTask task = tasks.selectByTaskIdForUpdate(taskId);
        if (task == null || !"COMPLETED".equals(task.getStatus()) || !"PENDING".equals(task.getTraceProjectionStatus())) return;
        try {
            Map<String, Object> trace = objectMapper.readValue(task.getIamSourceTrace(), new TypeReference<>() {});
            if (!"IAM-SIMPLE-1".equals(task.getIamProtocolVersion())
                    || !List.of("FINAL_PROTECTED", "FINAL_MASKED").contains(task.getIamFinalProtectionStatus())
                    || task.getPermissionRevision() == null
                    || !(trace.get("attemptId") instanceof String attemptId) || attemptId.isBlank()
                    || !(trace.get("sqlHash") instanceof String hash) || hash.isBlank()) reject();
            QueryAttempt attempt = attempts.selectForUpdate(taskId, (String) trace.get("attemptId"));
            if (attempt == null || !Objects.equals(taskId, attempt.getTaskId()) || !"PROTECTED".equals(attempt.getStatus())
                    || !Objects.equals(trace.get("sqlHash"), attempt.getSqlHash())
                    || !Objects.equals(task.getPermissionRevision(), attempt.getPermissionRevision())
                    || !sameInteger(task.getPermissionRevision(), trace.get("permissionRevision"))
                    || !sameInteger(task.getActiveMetadataSnapshotId(), trace.get("activeMetadataSnapshotId"))
                    || !Objects.equals(task.getActiveMetadataSnapshotId(), attempt.getActiveMetadataSnapshotId())
                    || !objectMapper.readTree(attempt.getSourceTrace()).equals(objectMapper.valueToTree(trace.get("entries")))
                    || !objectMapper.readTree(attempt.getUsedTables()).equals(objectMapper.readTree(task.getUsedTables()))
                    || !objectMapper.readTree(attempt.getUsedColumns()).equals(objectMapper.readTree(task.getUsedColumns()))) reject();
            List<Map<String, Object>> entries = objectMapper.convertValue(trace.get("entries"), new TypeReference<>() {});
            if (entries == null || entries.isEmpty()) reject();
            lineage.saveProtectedLineage(task, entries);
            audit.recordCommittedAudit(task);
            int updated = tasks.update(null, new LambdaUpdateWrapper<QueryTask>().eq(QueryTask::getId, task.getId())
                    .eq(QueryTask::getTraceProjectionStatus, "PENDING").set(QueryTask::getTraceProjectionStatus, "COMPLETED"));
            if (updated != 1) reject();
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("查询追溯投影失败，保留待恢复状态"); }
    }

    private void reject() { throw new BusinessException("查询追溯身份不匹配，不能生成成功记录"); }

    private boolean sameInteger(Long expected, Object raw) {
        if (expected == null || !(raw instanceof Number)) return false;
        try { return java.math.BigInteger.valueOf(expected).equals(new java.math.BigDecimal(raw.toString()).toBigIntegerExact()); }
        catch (ArithmeticException | NumberFormatException invalid) { return false; }
    }
}
