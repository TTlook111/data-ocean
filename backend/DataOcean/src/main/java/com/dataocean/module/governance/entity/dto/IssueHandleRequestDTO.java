package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class IssueHandleRequestDTO {

    @NotNull(message = "目标状态不能为空")
    private String status;

    private String resolutionNote;
}
