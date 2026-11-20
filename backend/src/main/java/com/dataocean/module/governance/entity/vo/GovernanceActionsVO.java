package com.dataocean.module.governance.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 给界面提供当前可以调用的操作按钮。
 */
@Data
@AllArgsConstructor
public class GovernanceActionsVO {

    /**
     * 可编辑。
     */
    private boolean canEdit;

    /**
     * 可生成建议。
     */
    private boolean canSuggest;

    /**
     * 可确认内容。
     */
    private boolean canConfirm;

    /**
     * 可请求补充。
     */
    private boolean canSupplement;

    /**
     * 可禁止使用。
     */
    private boolean canForbid;

    /**
     * 可确认移除。
     */
    private boolean canAcknowledgeRemoval;
}
