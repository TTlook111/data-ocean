package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import lombok.Data;

/**
 * 核对实际候选版本和完整输入指纹后才受理构建。
 */
@Data
public class KnowledgeReleaseBuildDTO {

    /**
     * 打开详情时看到的任务并发版本。
     */
    @NotNull
    @Positive
    private Integer expectedVersion;

    /**
     * 首次构建传就绪预检摘要，重试传已冻结摘要。取消准备任务时可省略。
     */
    @Pattern(regexp = "[0-9a-f]{64}")
    private String fingerprint;
}
