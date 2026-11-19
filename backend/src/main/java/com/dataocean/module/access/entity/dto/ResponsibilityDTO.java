package com.dataocean.module.access.entity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Data;
import lombok.ToString;

/**
 * 同一数据源的具体办理责任提案。
 */
@Data
public class ResponsibilityDTO {

    /**
     * 由服务端核对影响后签发的当前提案凭据。
     */
    @ToString.Exclude
    private String reviewToken;

    /**
     * 零表示尚未配置。
     */
    @NotNull
    @Min(0)
    private Integer expectedVersion;

    /**
     * 主审批人编号。
     */
    @NotBlank
    private String primaryUserId;

    /**
     * 替代负责人编号。
     */
    @NotBlank
    private String alternateUserId;
}
