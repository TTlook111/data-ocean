package com.dataocean.module.iam.entity.vo;

import java.util.List;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 历史角色恢复的影响核对结果，复用通用核对字段并补充恢复依据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@ToString(callSuper = true)
public final class RecoveryPreviewVO extends ImpactPreviewVO {

    /**
     * 与目标人员原有管理分配真实关联的历史审计标识。
     */
    private String sourceAuditId;

    /**
     * 经历史快照及当前资格核实的完整恢复分配。
     */
    private List<RoleVersionRefVO> restoredRoles;
}
