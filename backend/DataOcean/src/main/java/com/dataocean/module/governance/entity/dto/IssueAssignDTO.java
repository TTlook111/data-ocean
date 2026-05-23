package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 质量问题分派请求参数。
 */
@Data
public class IssueAssignDTO {

    @NotNull(message = "负责人ID不能为空")
    private Long assigneeId;
}
