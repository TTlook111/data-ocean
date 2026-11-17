package com.dataocean.module.iam.entity.po;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class OutboxPO {
    private String eventId;
    private String eventType;
    private int schemaVersion;
    private String aggregateType;
    private String aggregateId;
    private int aggregateVersion;
    private String commandId;
    private String payload;
    private String deliveryState;
    private int attempts;
    private LocalDateTime availableAt;
    private String leaseToken;
    private LocalDateTime leasedUntil;
    private LocalDateTime occurredAt;
    private LocalDateTime deliveredAt;
    private String lastErrorCode;
}
