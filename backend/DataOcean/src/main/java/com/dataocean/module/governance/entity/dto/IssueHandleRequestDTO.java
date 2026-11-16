package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 质量问题状态处理请求参数。
 */
@Data
public class IssueHandleRequestDTO {

    @NotNull(message = "目标状态不能为空")
    private String status;

    /** 固定用户读取问题时的版本，状态恢复成原值后也不能沿用旧处理依据。 */
    @NotNull(message = "问题预期版本不能为空")
    @Positive(message = "问题预期版本必须为正整数")
    private Long expectedVersion;

    private String resolutionNote;
}
