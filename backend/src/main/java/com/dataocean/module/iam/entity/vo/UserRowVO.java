package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class UserRowVO {
    private String id;
    private String username;
    private String displayName;
    private DepartmentRefVO primaryDepartment;
    private List<DepartmentRefVO> secondaryDepartments;
    private List<RoleRefVO> roles;
    private String displayStatus;
    private int version;
    private Instant updatedAt;
}
