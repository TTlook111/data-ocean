package com.dataocean.module.iam.entity.po;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.ToString;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude={"payloadHmac"})
public final class CommandPO {
    private String principalKind;
    private String principalId;
    private Long actorUserId;
    private String operationCode;
    private String commandId;
    private byte[] payloadHmac;
    private String hmacKeyVersion;
    private String state;
    private String resultCode;
    private String resultRef;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
