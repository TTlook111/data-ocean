package com.dataocean.module.audit.entity.po;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class AuditPO {
    private Long id;
    private String actorKind;
    private Long actorUserId;
    private String actorSnapshot;
    private String actionCode;
    private String targetType;
    private String targetId;
    private String targetName;
    private String outcome;
    private String reason;
    private String beforeSummary;
    private String afterSummary;
    private Long securityVersion;
    private String commandId;
    private String businessRequestId;
    private String traceId;
    private LocalDateTime occurredAt;
}
