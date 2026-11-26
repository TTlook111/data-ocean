package com.dataocean.module.access.entity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.Data;

/**
 * 授权撤销预览采用整单版本及用户给出的理由。
 */
@Data
public class RevokeAccessPreviewDTO {

    /**
     * 个人来源所属申请的当前版本。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 给出需要结束来源的实际原因。
     */
    @NotBlank
    @CodePointLength(max = 500)
    private String reason;
}
