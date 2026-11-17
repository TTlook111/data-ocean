package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class AccountVO {
    private String id;
    private String username;
    private String displayName;
    private String displayStatus;
    private DepartmentRefVO primaryDepartment;
    private List<DepartmentRefVO> secondaryDepartments;
    private List<RoleRefVO> roles;
    private String phone;
    private String email;
    private int version;
}
