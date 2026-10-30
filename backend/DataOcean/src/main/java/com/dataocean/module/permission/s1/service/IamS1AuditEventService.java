package com.dataocean.module.permission.s1.service;

/** IAM-SIMPLE-1 权限变更审计服务。 */
public interface IamS1AuditEventService {

    void recordSuccess(String eventType, Long operatorId, String targetType, Long targetId,
                       String beforeSummary, String afterSummary, String reason, String executionId);

    void recordFailure(String eventType, Long operatorId, String targetType, Long targetId,
                       String reason, String failureReason, String executionId);
}
