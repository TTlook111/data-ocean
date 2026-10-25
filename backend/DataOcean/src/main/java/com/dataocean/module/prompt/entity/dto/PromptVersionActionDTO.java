package com.dataocean.module.prompt.entity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 工作流动作绑定客户端实际查看的不可变版本，禁止默认选择最新版本。 */
@Data
public class PromptVersionActionDTO {
    @NotNull(message = "必须指定已查看的策略版本")
    @Min(value = 1, message = "策略版本必须大于零")
    private Integer expectedVersionNo;
    @Size(max = 500, message = "审核摘要不能超过 500 字符")
    private String changeSummary;
    @Size(max = 490, message = "驳回原因不能超过 490 字符")
    private String rejectReason;
}
