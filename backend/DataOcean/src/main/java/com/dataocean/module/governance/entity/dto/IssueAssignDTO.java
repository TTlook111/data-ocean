package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 质量问题分派请求参数。
 */
@Data
public class IssueAssignDTO {

    @NotNull(message = "负责人ID不能为空")
    private Long assigneeId;

    /** 分派与状态处理共享原版本，避免覆盖另一操作者刚修改的工作归属。 */
    @NotNull(message = "问题预期版本不能为空")
    @Positive(message = "问题预期版本必须为正整数")
    private Long expectedVersion;
}
