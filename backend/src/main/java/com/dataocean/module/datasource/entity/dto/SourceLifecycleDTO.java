package com.dataocean.module.datasource.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.Data;
import lombok.ToString;

/**
 * 数据源停用、恢复、退役采用同一生命周期请求结构。
 */
@Data
public class SourceLifecycleDTO {

    /**
     * 本次明确的管理动作。
     */
    @NotBlank
    private String action;

    /**
     * 用户核对的当前来源版本。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 保留原因及恢复实际采用的检查依据。
     */
    @NotNull
    @Valid
    private Proposal proposal;

    /**
     * 绑定本次操作者和完整提案的签名。
     */
    @NotBlank
    @ToString.Exclude
    private String reviewToken;

    /**
     * 使用同一结构绑定预览与正式提交。
     */
    @Data
    public static class Proposal {

        /**
         * 来源状态变更的实际原因。
         */
        @NotBlank
        @CodePointLength(max = 500)
        private String reason;

        /**
         * 恢复专用的当前来源连接检查。
         */
        private String checkId;
    }
}
