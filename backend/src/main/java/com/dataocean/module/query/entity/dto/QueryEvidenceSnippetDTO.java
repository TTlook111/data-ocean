package com.dataocean.module.query.entity.dto;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 业务模型使用的正式知识片段与受控列、指标。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryEvidenceSnippetDTO {

    /**
     * 生成调用可采用的正式绑定编号。
     */
    private String binding;

    /**
     * 经过现行规则允许返回的文字内容。
     */
    private String text;

    /**
     * 该正式片段实际允许模型选择的表与字段。
     */
    private Map<String, Map<String, String>> tables;

    /**
     * 已发布口径中实际可采用的指标。
     */
    private List<Metric> metrics;

    /**
     * 已发布材料中的受控指标定义。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Metric {

        /**
         * 业务发布中确认的指标名称。
         */
        private String name;

        /**
         * 指标已有的业务含义。
         */
        private String meaning;

        /**
         * 治理确认后的正式口径规则。
         */
        private String rule;

        /**
         * 指标在真实场景中的用法。
         */
        private String usage;
    }
}
