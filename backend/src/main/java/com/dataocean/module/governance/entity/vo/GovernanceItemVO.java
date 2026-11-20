package com.dataocean.module.governance.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 治理条目详情及当前可办理动作。
 */
@Data
@AllArgsConstructor
public class GovernanceItemVO {

    /**
     * 当前稳定条目。
     */
    private GovernanceTargetVO target;

    /**
     * 持久化条目编号，空条目可为空。
     */
    private String itemId;

    /**
     * 当前并发版本，未落库为空。
     */
    private Integer version;

    /**
     * 当前适用或最后成功的来源结构。
     */
    private GovernanceBasisVO structureBasis;

    /**
     * 空白、草稿或有效确认。
     */
    private String state;

    /**
     * 存在来源影响待处理。
     */
    private boolean pendingReview;

    /**
     * 可披露的当前内容。
     */
    private GovernanceRevisionVO currentRevision;

    /**
     * 最后一次实际人工确认。
     */
    private GovernanceConfirmationVO latestConfirmation;

    /**
     * 可披露的具体核对事项。
     */
    private List<GovernanceGapVO> gaps;

    /**
     * 来源变化及进度。
     */
    private List<GovernanceImpactVO> impacts;

    /**
     * 当前生产使用是否被禁止。
     */
    private boolean blocked;

    /**
     * 当前结构治理可用性。
     */
    private String availability;

    /**
     * 当前原字段或表说明的展示名称。
     */
    private String displayName;

    /**
     * 字段的原生类型。
     */
    private String nativeType;

    /**
     * 当前固定结构的原注释。
     */
    private String sourceComment;

    /**
     * 当前各动作按钮是否具备权限。
     */
    private GovernanceActionsVO actions;
}
