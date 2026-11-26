package com.dataocean.module.feedback.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前技术负责人提交已生效的处理结果与可独立复查的记录。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubmitIssueResultDTO {

    /**
     * 操作人核对时所见的事项版本。
     */
    @Positive
    private int expectedVersion;

    /**
     * 技术修复的影响类别。
     */
    @NotBlank
    private String impact;

    /**
     * 处理人确认的实际修正范围或内容。
     */
    @NotBlank
    @Size(max = 1000)
    private String summary;

    /**
     * 实际采用的复查方式及结果。
     */
    @NotBlank
    @Size(max = 1000)
    private String checkMethod;

    /**
     * 修复或源系统工单的参考编号。
     */
    @Size(max = 500)
    private String reference;

    /**
     * 当前版本的修正已经实际生效。
     */
    private boolean effectiveConfirmed;

    /**
     * 相关查询或安全场景的回归依据。
     */
    @Size(max = 1000)
    private String regression;

    /**
     * 相关场景是否通过回归核对。
     */
    private Boolean regressionPassed;
}
