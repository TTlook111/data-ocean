package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 验证动作通过原任务版本确定可写的当前阶段。
 */
@Data
public class KnowledgeValidationDTO {

    /**
     * 当前发布任务的实际聚合版本。
     */
    @NotNull
    @Positive
    private Integer expectedVersion;

    /**
     * 手动运行绑定页面看到的已成功索引代次。
     */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Start extends KnowledgeValidationDTO {

        /**
         * 已完成实际读回的构建编号。
         */
        @NotBlank
        @Size(max = 36)
        private String buildId;
    }

    /**
     * 人工结论及依据，仅用于待裁定说明题。
     */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Judgment extends KnowledgeValidationDTO {

        /**
         * 当前人工判定结论。
         */
        @NotBlank
        @Pattern(regexp = "PASSED|FAILED")
        private String decision;

        /**
         * 对本轮证据作出的实际判断。
         */
        @NotBlank
        @Size(max = 300)
        private String reason;
    }
}
