package com.dataocean.module.governance.entity.vo;

import com.dataocean.module.governance.entity.dto.EvidenceLocationDTO;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 受当前权限约束的具体引用。
 */
@Data
@AllArgsConstructor
public class GovernanceEvidenceVO {

    /**
     * 依据类型。
     */
    private String kind;

    /**
     * 对应业务字段。
     */
    private String contentPath;

    /**
     * 当前用户是否只可见遮蔽摘要。
     */
    private boolean redacted;

    /**
     * 获准的固定结构依据。
     */
    private GovernanceBasisVO structure;

    /**
     * 获准资料编号。
     */
    private String noteId;

    /**
     * 获准资料修订主键。
     */
    private String revisionId;

    /**
     * 资料修订序号。
     */
    private Integer revision;

    /**
     * 获准附件编号。
     */
    private String attachmentId;

    /**
     * 实际引用位置。
     */
    private EvidenceLocationDTO location;

    /**
     * 可披露的人工核对依据。
     */
    private String manualBasis;
}
