package com.dataocean.module.permission.s1.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.entity.dto.IamS1RowConditionDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.regex.Pattern;

/** S1 结构化记录条件校验器；输入不会被拼接为 SQL。 */
public final class IamS1RowConditionValidator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> OPERATORS = Set.of("EQ", "NE", "GT", "GE", "LT", "LE", "IN", "NOT_IN",
            "IS_NULL", "IS_NOT_NULL");
    private static final Set<String> VALUE_TYPES = Set.of("STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE",
            "DATETIME", "STRING_LIST", "INTEGER_LIST", "DECIMAL_LIST", "NULL");
    private static final Pattern PARAMETER_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{2,99}");

    private IamS1RowConditionValidator() {
    }

    public static String validateAndCanonicalize(IamS1RowConditionDTO condition, String metadataDataType) {
        if (condition == null || condition.getOperatorCode() == null || condition.getValueType() == null) {
            throw new BusinessException("记录条件缺少操作符或值类型");
        }
        String operator = condition.getOperatorCode().trim().toUpperCase();
        String valueType = condition.getValueType().trim().toUpperCase();
        if (!OPERATORS.contains(operator) || !VALUE_TYPES.contains(valueType)) {
            throw new BusinessException("记录条件操作符或值类型不在白名单");
        }
        boolean nullOperator = "IS_NULL".equals(operator) || "IS_NOT_NULL".equals(operator);
        boolean hasValue = condition.getStructuredValueJson() != null
                && !condition.getStructuredValueJson().isBlank();
        boolean hasParameter = condition.getParameterReference() != null
                && !condition.getParameterReference().isBlank();
        if (nullOperator) {
            if (!"NULL".equals(valueType) || hasValue || hasParameter) {
                throw new BusinessException("空值条件不得携带普通值或参数");
            }
            return null;
        }
        if ("NULL".equals(valueType) || hasValue == hasParameter) {
            throw new BusinessException("记录条件必须且只能提供一个结构化值或服务端参数引用");
        }
        if (hasParameter) {
            String reference = condition.getParameterReference().trim();
            if (!PARAMETER_PATTERN.matcher(reference).matches()
                    || reference.contains("CURRENT_USER") || reference.contains("CURRENT_DEPARTMENT")) {
                throw new BusinessException("参数引用不合法，身份参数必须由服务端上下文提供");
            }
            ensureTypeCompatible(valueType, metadataDataType);
            return null;
        }
        JsonNode node;
        try {
            node = OBJECT_MAPPER.readTree(condition.getStructuredValueJson());
        } catch (Exception exception) {
            throw new BusinessException("记录条件值必须是合法结构化 JSON");
        }
        if (node == null || node.isObject() || node.isMissingNode()) {
            throw new BusinessException("记录条件值不允许对象或缺失节点");
        }
        boolean collectionOperator = "IN".equals(operator) || "NOT_IN".equals(operator);
        boolean collectionType = valueType.endsWith("_LIST");
        if (collectionOperator != collectionType) {
            throw new BusinessException("集合操作符必须使用集合值类型");
        }
        validateNodeType(node, valueType);
        ensureTypeCompatible(valueType, metadataDataType);
        return node.toString();
    }

    private static void validateNodeType(JsonNode node, String valueType) {
        if (valueType.endsWith("_LIST")) {
            if (!node.isArray() || node.isEmpty()) {
                throw new BusinessException("集合条件必须是非空数组");
            }
            String scalarType = valueType.substring(0, valueType.length() - 5);
            for (JsonNode item : node) {
                validateNodeType(item, scalarType);
            }
            return;
        }
        boolean valid = switch (valueType) {
            case "STRING", "DATE", "DATETIME" -> node.isTextual();
            case "INTEGER" -> node.isIntegralNumber();
            case "DECIMAL" -> node.isNumber();
            case "BOOLEAN" -> node.isBoolean();
            default -> false;
        };
        if (!valid) {
            throw new BusinessException("记录条件值类型与声明不匹配");
        }
        if ("DATE".equals(valueType)) {
            try {
                LocalDate.parse(node.textValue());
            } catch (Exception exception) {
                throw new BusinessException("日期条件值格式不合法");
            }
        }
        if ("DATETIME".equals(valueType)) {
            try {
                LocalDateTime.parse(node.textValue());
            } catch (Exception first) {
                try {
                    OffsetDateTime.parse(node.textValue());
                } catch (Exception second) {
                    throw new BusinessException("时间条件值格式不合法");
                }
            }
        }
        if ("DECIMAL".equals(valueType)) {
            try {
                new BigDecimal(node.asText());
            } catch (Exception exception) {
                throw new BusinessException("数值条件值格式不合法");
            }
        }
    }

    private static void ensureTypeCompatible(String valueType, String metadataDataType) {
        if (metadataDataType == null || metadataDataType.isBlank()) {
            throw new BusinessException("条件字段类型未知");
        }
        String type = metadataDataType.toUpperCase();
        boolean compatible = switch (valueType) {
            case "STRING", "STRING_LIST" -> type.contains("CHAR") || type.contains("TEXT")
                    || type.contains("ENUM") || type.contains("JSON");
            case "INTEGER", "INTEGER_LIST" -> type.contains("INT") || type.contains("YEAR")
                    || type.contains("BIT");
            case "DECIMAL", "DECIMAL_LIST" -> type.contains("DECIMAL") || type.contains("NUMERIC")
                    || type.contains("DOUBLE") || type.contains("FLOAT") || type.contains("REAL");
            case "BOOLEAN" -> type.contains("BOOL") || type.contains("BIT");
            case "DATE" -> type.contains("DATE");
            case "DATETIME" -> type.contains("TIME") || type.contains("DATE");
            default -> false;
        };
        if (!compatible) {
            throw new BusinessException("条件值类型与元数据字段类型不匹配");
        }
    }
}
