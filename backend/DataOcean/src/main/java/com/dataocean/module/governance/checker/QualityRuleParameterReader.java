package com.dataocean.module.governance.checker;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;

/** 只读取注册模板的结构化数值参数。参数校验由规则服务负责，损坏的持久化配置必须报错。 */
public final class QualityRuleParameterReader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private QualityRuleParameterReader() {}

    public static BigDecimal decimal(MetadataQualityRule rule, String key, BigDecimal fallback) {
        Object value = read(rule).get(key);
        if (value == null) return fallback;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            throw new BusinessException(409, "质量规则参数无效: " + key);
        }
    }

    public static int integer(MetadataQualityRule rule, String key, int fallback) {
        BigDecimal value = decimal(rule, key, null);
        if (value == null) return fallback;
        try {
            return value.intValueExact();
        } catch (ArithmeticException exception) {
            throw new BusinessException(409, "质量规则参数无效: " + key);
        }
    }

    public static boolean bool(MetadataQualityRule rule, String key, boolean fallback) {
        Object value = read(rule).get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean flag) return flag;
        throw new BusinessException(409, "质量规则参数无效: " + key);
    }

    private static Map<String, Object> read(MetadataQualityRule rule) {
        if (rule == null || rule.getParametersJson() == null || rule.getParametersJson().isBlank()) return Map.of();
        try {
            return MAPPER.readValue(rule.getParametersJson(), new TypeReference<>() {});
        } catch (Exception exception) {
            throw new BusinessException(409, "质量规则结构化参数无法解析");
        }
    }
}
