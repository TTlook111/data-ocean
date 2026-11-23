package com.dataocean.module.feedback.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 核查依据必须填写，原因未明时允许留空。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewIssueDTO {

    /**
     * 操作人核对时所见的事项版本。
     */
    @Positive
    private int expectedVersion;

    /**
     * 支撑事项核查或人工结论的依据。
     */
    @NotBlank
    @Size(max = 1000)
    private String basis;

    /**
     * 当前确认的根因，尚未核实时为空。
     */
    @Size(max = 500)
    private String rootCause;
}
