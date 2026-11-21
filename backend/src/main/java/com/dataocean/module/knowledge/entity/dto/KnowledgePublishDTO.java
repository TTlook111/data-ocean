package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 发布、核对和结果确认均基于页面读取的实际任务版本。
 */
@Data
public class KnowledgePublishDTO {

    /**
     * 本次操作对应的任务聚合版本。
     */
    @NotNull
    @Positive
    private Integer expectedVersion;

    /**
     * 预检明确声明本次办理的动作。
     */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Preview extends KnowledgePublishDTO {

        /**
         * 本切面仅处理正式发布。
         */
        @NotBlank
        @Pattern(regexp = "PUBLISH")
        private String action;
    }

    /**
     * 确认发布只能使用本人核对过的实际范围。
     */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Confirm extends KnowledgePublishDTO {

        /**
         * 有界核对凭据，不能写入诊断日志。
         */
        @NotBlank
        @Size(max = 4096)
        @ToString.Exclude
        private String reviewToken;
    }
}
