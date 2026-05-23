package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 质量问题状态处理请求参数。
 */
@Data
public class IssueHandleRequestDTO {

    @NotNull(message = "目标状态不能为空")
    private String status;

    private String resolutionNote;
}
