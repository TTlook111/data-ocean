package com.dataocean.module.feedback.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分派人确认当前事项版本、有效负责人和分派依据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssignIssueDTO {

    /**
     * 操作人核对时所见的事项版本。
     */
    @Positive
    private int expectedVersion;

    /**
     * 本次分派所选负责人的编号。
     */
    @NotBlank
    private String assigneeId;

    /**
     * 反馈事项拟采用的办理方向。
     */
    @NotBlank
    private String direction;

    /**
     * 此动作采用的实际业务理由。
     */
    @NotBlank
    @Size(max = 1000)
    private String reason;
}
