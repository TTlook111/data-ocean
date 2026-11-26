package com.dataocean.module.governance.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 写入或重放后取得的当前安全实体引用。
 */
@Data
@AllArgsConstructor
public class GovernanceMutationVO {

    /**
     * 实际业务命令。
     */
    private String commandId;

    /**
     * 影响的表说明或字段。
     */
    private GovernanceTargetVO target;

    /**
     * 已建立的条目编号。
     */
    private String itemId;

    /**
     * 本次写入的条目版本。
     */
    private Integer version;

    /**
     * 可披露内容修订。
     */
    private GovernanceRevisionVO currentRevision;

    /**
     * 本命令实际确认编号。
     */
    private String confirmationId;

    /**
     * 本命令实际结转的影响。
     */
    private List<String> resolvedImpactIds;

    /**
     * 当前是否禁止使用。
     */
    private boolean blocked;
}
