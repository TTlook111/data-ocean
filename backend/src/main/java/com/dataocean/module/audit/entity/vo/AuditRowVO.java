package com.dataocean.module.audit.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class AuditRowVO {
    private String id;
    private AuditActorVO actor;
    private String actionCode;
    private String targetType;
    private String targetId;
    private String targetName;
    private String outcome;
    private Instant occurredAt;
}
