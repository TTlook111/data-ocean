package com.dataocean.module.feedback.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 需要凭依据办理的事项纠正或重开操作。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackIssueReasonDTO {

    /**
     * 当前事项版本，旧表单不可覆盖近期变化。
     */
    @Positive
    private int expectedVersion;

    /**
     * 用于说明纠正、复发或新证据的核实依据。
     */
    @NotBlank
    @Size(max = 1000)
    private String reason;
}
