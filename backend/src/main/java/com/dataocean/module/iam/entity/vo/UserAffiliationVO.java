package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class UserAffiliationVO {
    private DepartmentRefVO primaryDepartment;
    private List<DepartmentRefVO> secondaryDepartments;
    private List<RoleRefVO> roles;
}
