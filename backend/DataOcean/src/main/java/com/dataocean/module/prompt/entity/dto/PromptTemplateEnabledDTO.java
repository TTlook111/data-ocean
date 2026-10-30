package com.dataocean.module.prompt.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Prompt 模板启停请求。
 * <p>
 * `prompt_template.enabled` 决定 `getActiveContent` 能否取到该模板，此前只有
 * `approve` 会把它置为 true，没有任何修改接口，前端只能展示、无法停用。
 * </p>
 *
 * @author DataOcean
 */
@Data
public class PromptTemplateEnabledDTO {

    /** 是否启用 */
    @NotNull(message = "enabled 不能为空")
    private Boolean enabled;
}
