package com.dataocean.module.feedback.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 主负责人对一条仍在等待的补充请求作出的处置。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DisposeFeedbackSupplementDTO {

    /**
     * 所见主事项版本，原提问人回复或关联变化后须重新核对。
     */
    @Positive
    private int expectedVersion;

    /**
     * 撤回不再需要的请求，或记录无法取得补充的事实。
     */
    @NotBlank
    @Pattern(regexp = "WITHDRAWN|UNAVAILABLE")
    private String disposition;

    /**
     * 处置依据，只在有权读取该原问题证据的后台投影中显示。
     */
    @NotBlank
    @Size(max = 1000)
    private String reason;
}
