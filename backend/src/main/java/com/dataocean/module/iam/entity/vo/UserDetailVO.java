package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class UserDetailVO {
    private String id;
    private String username;
    private String displayName;
    private DepartmentRefVO primaryDepartment;
    private List<DepartmentRefVO> secondaryDepartments;
    private List<RoleRefVO> roles;
    private String displayStatus;
    private int version;
    private Instant updatedAt;
    private String lifecycle;
    private boolean mustChangePassword;
    private String phone;
    private String email;
    private String managementNote;
    private Instant firstLoginAt;
    private Instant disabledAt;
    private Instant archivedAt;
}
