package com.dataocean.module.iam.entity.po;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class RoleAssignmentPO {
    private Long id;
    private Long userId;
    private Long roleId;
    private int assignedRoleVersion;
    private Long assignedBy;
    private LocalDateTime assignedAt;
    private Long endedBy;
    private LocalDateTime endedAt;
}
