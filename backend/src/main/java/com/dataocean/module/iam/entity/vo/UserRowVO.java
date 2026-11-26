package com.dataocean.module.iam.entity.vo;

import java.time.Instant;
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
public class UserRowVO {

    /**
     * 该记录的稳定标识，对外长整型以十进制字符串表达。
     */
    private String id;

    /**
     * 创建后稳定的登录名，按契约精确匹配。
     */
    private String username;

    /**
     * 人员当前显示姓名，不作为登录或授权依据。
     */
    private String displayName;

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

    /**
     * 由生命周期、首次登录和待改密事实推导的展示状态。
     */
    private String displayStatus;

    /**
     * 该业务聚合的当前修订，写操作使用它检查并发变化。
     */
    private int version;

    /**
     * 该记录最近实际变化的 UTC 时刻。
     */
    private Instant updatedAt;
}
