package com.dataocean.module.access.entity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.Data;
import lombok.ToString;

/**
 * 保存共享输出限制的明确提案。
 */
@Data
public class OutputRuleDTO {

    /**
     * 由服务端核对影响后签发的当前提案凭据。
     */
    @ToString.Exclude
    private String reviewToken;

    /**
     * 编辑使用已有规则版本。
     */
    @Min(1)
    private Integer expectedVersion;

    /**
     * 规则名称。
     */
    @NotBlank
    @CodePointLength(max = 80)
    private String name;

    /**
     * 中文限制全文。
     */
    @NotBlank
    @CodePointLength(max = 2000)
    private String content;
}
