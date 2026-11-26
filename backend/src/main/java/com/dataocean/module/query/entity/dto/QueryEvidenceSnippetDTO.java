package com.dataocean.module.query.entity.dto;

import com.dataocean.module.query.service.QueryProtocol.Condition;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.fasterxml.jackson.annotation.JsonInclude;

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

        /**
         * 已确认且在当前片段可核对的执行规则。字段用模型表列协议表示。
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private ConfirmedMetricRules metricRules;
    }

    /**
     * 规划和复核共用的已发布执行规则，字段在当前片段内可定位。
     *
     * @param dateMode 是否按已确认的业务日查询
     * @param dateField 需要业务日时的实际日期列
     * @param fixedFilters 指标实际必须满足的等值条件，空列表明确无固定筛选
     * @param unitMode 固定单位、同列值核验或无单位
     * @param unit 固定的单位或币种
     * @param unitField 币种或单位来自源列时需核验的字段
     */
    public record ConfirmedMetricRules(
            String dateMode,
            Field dateField,
            List<Condition> fixedFilters,
            String unitMode,
            String unit,
            Field unitField) {}
}
