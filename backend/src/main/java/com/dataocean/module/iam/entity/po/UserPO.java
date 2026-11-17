package com.dataocean.module.iam.entity.po;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class UserPO {
    private Long id;
    private String username;
    private String displayName;
    private String phone;
    private String email;
    private String managementNote;
    private String lifecycle;
    private boolean mustChangePassword;
    private long authenticationVersion;
    private int version;
    private LocalDateTime firstLoginAt;
    private LocalDateTime disabledAt;
    private LocalDateTime archivedAt;
    private String archivedAffiliation;
    private String statusReason;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
