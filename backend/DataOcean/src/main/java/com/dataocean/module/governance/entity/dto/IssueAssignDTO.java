package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class IssueAssignDTO {

    @NotNull(message = "负责人ID不能为空")
    private Long assigneeId;
}
