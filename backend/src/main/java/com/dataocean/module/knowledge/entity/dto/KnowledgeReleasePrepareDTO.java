package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import lombok.Data;

/**
 * 固定当前页面确认的单文档发布范围。
 */
@Data
public class KnowledgeReleasePrepareDTO {

    /**
     * 当前源内的知识文档。
     */
    @NotBlank
    private String documentId;

    /**
     * 界面核对过的确切文档确认。
     */
    @NotBlank
    private String documentRevisionId;

    /**
     * 本次预检确认的完整候选依据，使用 SHA-256 十六进制表示。
     */
    @NotBlank
    @Pattern(regexp = "[0-9a-f]{64}")
    private String inputFingerprint;
}
