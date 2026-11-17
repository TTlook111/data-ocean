package com.dataocean.module.iam.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 人员资料的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class UserAffiliationVO {

    /**
     * 人员当前或归档时的主部门安全引用。
     */
    private DepartmentRefVO primaryDepartment;

    /**
     * 人员当前或归档时的兼任部门安全引用。
     */
    private List<DepartmentRefVO> secondaryDepartments;

    /**
     * 当前角色、使用与授予集合及分配历史的访问依赖。
     */
    private List<RoleRefVO> roles;
}
