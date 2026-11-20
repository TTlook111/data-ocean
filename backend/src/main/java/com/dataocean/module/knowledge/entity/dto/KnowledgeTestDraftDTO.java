package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 标准题预期和实际执行条件，两种判定方法共用题干。
 */
@Data
public class KnowledgeTestDraftDTO {

    /**
     * 题目面向的实际文档。
     */
    @NotBlank
    private String documentId;

    /**
     * 固定本次准备时阅读的文档确认。
     */
    @NotBlank
    private String documentRevisionId;

    /**
     * 题目正文。
     */
    @NotBlank
    @Size(max = 300)
    private String question;

    /**
     * 首轮支持关键能力及使用边界两类覆盖。
     */
    @NotBlank
    @Pattern(regexp = "CAPABILITY|BOUNDARY")
    private String coverage;

    /**
     * 数值自动比较或按证据人工裁定。
     */
    @NotBlank
    @Pattern(regexp = "NUMERIC|EXPLANATION")
    private String mode;

    /**
     * 独立于实际执行结果的期望数值。
     */
    @PositiveOrZero
    private Long expectedCount;

    /**
     * 说明题的人工判定依据。
     */
    @Size(max = 3000)
    private String expectedExplanation;

    /**
     * 说明合成输入、预期或实际证据依据。
     */
    @Size(max = 1000)
    private String evidence;

    /**
     * 已核对的结构化去重计数条件。
     */
    @Valid
    private CountRule countRule;

    /**
     * 只支持服务端可验证的既有治理身份，浏览器不提交 SQL。
     */
    @Data
    public static class CountRule {

        /**
         * 文档中已确认的去重计数指标。
         */
        private String metricId;

        /**
         * 文档中已确认的商品到展示关系。
         */
        private String relationId;

        /**
         * 文档中已确认的商品分类字段。
         */
        private String categoryId;

        /**
         * 具体测试分类，执行阶段由驱动绑定。
         */
        @Size(max = 80)
        private String categoryValue;
    }
}
