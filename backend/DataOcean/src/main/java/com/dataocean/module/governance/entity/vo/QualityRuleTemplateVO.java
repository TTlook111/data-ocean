package com.dataocean.module.governance.entity.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class QualityRuleTemplateVO {
    private String code;
    private String name;
    private String dimension;
    private String targetType;
    private String checkType;
    private String description;
    private List<String> allowedScopes;
    private List<ParameterVO> parameters;

    @Data
    public static class ParameterVO {
        private String key;
        private String label;
        private String type;
        private BigDecimal minimum;
        private BigDecimal maximum;
        private Object defaultValue;
    }
}
