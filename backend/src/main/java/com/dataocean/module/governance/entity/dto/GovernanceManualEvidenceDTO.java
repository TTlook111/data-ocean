package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 人工补充结论或引用确切资料修订。
 *
 * @param contentPath 证据支持的业务内容字段
 * @param noteId 企业业务说明编号
 * @param revisionId 资料的不可变修订编号
 * @param attachmentId 所引用的该修订附件编号
 * @param location 资料正文或原件中的实际位置
 * @param manualBasis 人工提供的业务依据
 */
public record GovernanceManualEvidenceDTO(
        @NotBlank String contentPath,
        String noteId,
        String revisionId,
        String attachmentId,
        EvidenceLocationDTO location,
        @Size(max = 1000) String manualBasis) {}
