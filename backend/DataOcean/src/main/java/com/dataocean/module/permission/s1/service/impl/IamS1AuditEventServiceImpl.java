package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.entity.IamS1AuditEvent;
import com.dataocean.module.permission.s1.mapper.IamS1AuditEventMapper;
import com.dataocean.module.permission.s1.service.IamS1AuditEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/** IAM-SIMPLE-1 权限审计服务实现。 */
@Service
@RequiredArgsConstructor
public class IamS1AuditEventServiceImpl implements IamS1AuditEventService {

    private final IamS1AuditEventMapper auditEventMapper;

    @Override
    public void recordSuccess(String eventType, Long operatorId, String targetType, Long targetId,
                              String beforeSummary, String afterSummary, String reason, String executionId) {
        IamS1AuditEvent event = baseEvent(eventType, operatorId, targetType, targetId, reason, executionId);
        event.setBeforeSummary(safeSummary(beforeSummary));
        event.setAfterSummary(safeSummary(afterSummary));
        event.setSuccess(1);
        auditEventMapper.insert(event);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String eventType, Long operatorId, String targetType, Long targetId,
                              String reason, String failureReason, String executionId) {
        IamS1AuditEvent event = baseEvent(eventType, operatorId, targetType, targetId, reason, executionId);
        event.setSuccess(0);
        event.setFailureReason(safeSummary(failureReason));
        auditEventMapper.insert(event);
    }

    private IamS1AuditEvent baseEvent(String eventType, Long operatorId, String targetType, Long targetId,
                                      String reason, String executionId) {
        if (eventType == null || eventType.isBlank() || targetType == null || targetType.isBlank()) {
            throw new BusinessException("权限审计缺少事件或目标类型");
        }
        IamS1AuditEvent event = new IamS1AuditEvent();
        event.setEventType(eventType);
        event.setOperatorId(operatorId);
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setReason(safeSummary(reason));
        event.setExecutionId(safeSummary(executionId));
        return event;
    }

    private String safeSummary(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("password") || lower.contains("jwt") || lower.contains("api_key")
                || lower.contains("secret") || lower.contains("token") || lower.contains("database")) {
            throw new BusinessException("权限审计摘要包含禁止记录的敏感字段");
        }
        if (normalized.length() > 2000) {
            throw new BusinessException("权限审计摘要过长");
        }
        return normalized;
    }
}
