package com.dataocean.module.prompt.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Prompt 模板更新请求 DTO
 * <p>
 * 更新模板内容时自动创建新版本。
 * </p>
 */
@Data
public class PromptTemplateUpdateDTO {

    /** 新的模板内容 */
    @NotBlank(message = "模板内容不能为空")
    private String content;

    /** 变更摘要说明（可选） */
    private String changeSummary;
}
