package com.dataocean.module.iam.entity.po;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 某一角色修订的不可变快照，历史内容不能直接用于当前放行。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class RoleSnapshotPO {

    /**
     * 当前角色的稳定标识，不按角色名称引用。
     */
    private Long roleId;

    /**
     * 被引用角色快照的修订版本。
     */
    private int roleVersion;

    /**
     * 当前业务对象的显示名称，身份仍使用稳定标识。
     */
    private String name;

    /**
     * 当前对象面向业务人员的职责说明。
     */
    private String description;

    /**
     * 正常、停用或归档的持久生命周期状态。
     */
    private String lifecycle;

    /**
     * 完整最终功能使用集合，与可授予集合分别核对。
     */
    private String permissionCodes;

    /**
     * 显式可授予功能集合，与本人使用权分别维护。
     */
    private String grantablePermissionCodes;

    /**
     * 产生该版本变化的操作人员标识。
     */
    private Long changedBy;

    /**
     * 该凭据或快照变化实际发生的 UTC 时刻。
     */
    private LocalDateTime changedAt;
}
