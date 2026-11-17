package com.dataocean.module.iam.entity.po;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class RoleSnapshotPO {
    private Long roleId;
    private int roleVersion;
    private String name;
    private String description;
    private String lifecycle;
    private String permissionCodes;
    private String grantablePermissionCodes;
    private Long changedBy;
    private LocalDateTime changedAt;
}
