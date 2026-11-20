package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 人工补充结论或引用确切资料修订。
 */
@Data
public class GovernanceManualEvidenceDTO {

    /**
     * 证据支持的业务内容字段。
     */
    @NotBlank
    private String contentPath;

    /**
     * 企业业务说明编号。
     */
    private String noteId;

    /**
     * 资料的不可变修订编号。
     */
    private String revisionId;

    /**
     * 所引用的该修订附件编号。
     */
    private String attachmentId;

    /**
     * 资料正文或原件中的实际位置。
     */
    private EvidenceLocationDTO location;

    /**
     * 人工提供的业务依据。
     */
    @Size(max = 1000)
    private String manualBasis;
}
