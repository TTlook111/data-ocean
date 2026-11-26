package com.dataocean.module.feedback.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 负责人把知识修订及其覆盖原问题的依据提交给复验人。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubmitKnowledgeResultDTO {

    /**
     * 操作人核对时所见的事项版本。
     */
    @Positive
    private int expectedVersion;

    /**
     * 已生效且覆盖范围的正式发布绑定。
     */
    @NotBlank
    private String bindingId;

    /**
     * 处理人确认的实际修正范围或内容。
     */
    @NotBlank
    @Size(max = 1000)
    private String summary;

    /**
     * 实际采用的复查方式及结果。
     */
    @NotBlank
    @Size(max = 1000)
    private String checkMethod;
}
