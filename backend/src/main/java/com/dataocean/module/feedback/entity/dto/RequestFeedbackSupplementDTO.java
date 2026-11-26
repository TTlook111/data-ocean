package com.dataocean.module.feedback.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 主负责人在核查后向确切原问请求缺失的业务信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RequestFeedbackSupplementDTO {

    /**
     * 提交前所见的主事项版本。
     */
    @Positive
    private int expectedVersion;

    /**
     * 需要补充的原问编号，由服务端核对其所属事项及原提问人。
     */
    @NotBlank
    @Size(max = 36)
    private String requestId;

    /**
     * 只包含原提问人需要补充的具体信息。
     */
    @NotBlank
    @Size(max = 1000)
    private String prompt;
}
