package com.dataocean.module.iam.entity.po;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 保存个人角色分配及其当时版本，结束关系保留可核验历史。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class RoleAssignmentPO {

    /**
     * 该记录的稳定标识，对外长整型以十进制字符串表达。
     */
    private Long id;

    /**
     * 当前人员的稳定标识，客户端自报值不能替代登录身份。
     */
    private Long userId;

    /**
     * 当前角色的稳定标识，不按角色名称引用。
     */
    private Long roleId;

    /**
     * 建立分配时对应的不可变角色版本。
     */
    private int assignedRoleVersion;

    /**
     * 建立该角色分配的操作人员标识。
     */
    private Long assignedBy;

    /**
     * 该角色分配实际建立的 UTC 时刻。
     */
    private LocalDateTime assignedAt;

    /**
     * 结束该角色分配的操作人员标识。
     */
    private Long endedBy;

    /**
     * 角色分配结束的 UTC 时刻，空值表示当前分配。
     */
    private LocalDateTime endedAt;
}
