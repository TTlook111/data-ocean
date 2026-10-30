package com.dataocean.module.permission.s1.support;

import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.catalog.IamS1FunctionCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * IAM-SIMPLE-1 管理界面的中文术语映射。
 * <p>
 * 页面避免暴露 scope、effect、policy、revision 等技术词，所有枚举一律翻译为中文动作与结果。
 * </p>
 */
public final class IamS1Labels {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Map<String, String> OPERATOR_NAMES = Map.ofEntries(
            Map.entry("EQ", "等于"), Map.entry("NE", "不等于"),
            Map.entry("GT", "大于"), Map.entry("GE", "大于等于"),
            Map.entry("LT", "小于"), Map.entry("LE", "小于等于"),
            Map.entry("IN", "属于"), Map.entry("NOT_IN", "不属于"),
            Map.entry("IS_NULL", "为空"), Map.entry("IS_NOT_NULL", "不为空"));

    private static final Map<String, String> MASK_POLICY_NAMES = Map.of(
            "PHONE", "手机号掩码", "ID_CARD", "身份证掩码", "EMAIL", "邮箱掩码",
            "BANK_CARD", "银行卡掩码", "NAME", "姓名掩码");

    private IamS1Labels() {
    }

    public static String subjectTypeName(String subjectType) {
        if (subjectType == null) {
            return "未选择主体";
        }
        return switch (subjectType) {
            case IamS1Constants.SUBJECT_USER -> "用户";
            case IamS1Constants.SUBJECT_ROLE -> "角色";
            case IamS1Constants.SUBJECT_DEPARTMENT -> "部门";
            default -> subjectType;
        };
    }

    public static String departmentScopeName(String scope) {
        if (scope == null) {
            return null;
        }
        return switch (scope) {
            case IamS1Constants.DEPARTMENT_SCOPE_SELF -> "仅本部门";
            case IamS1Constants.DEPARTMENT_SCOPE_INCLUDE_DESCENDANTS -> "包含下级部门";
            default -> scope;
        };
    }

    public static String effectName(String effect) {
        if (effect == null) {
            return null;
        }
        return IamS1Constants.EFFECT_ALLOW.equals(effect) ? "允许查询" : "禁止查询";
    }

    public static String resourceScopeName(String scope) {
        if (scope == null) {
            return null;
        }
        return IamS1Constants.RESOURCE_SCOPE_DATASOURCE.equals(scope) ? "整个数据源" : "指定的表";
    }

    public static String protectionLevelName(String level) {
        if (level == null) {
            return null;
        }
        return switch (level) {
            case IamS1Constants.PROTECTION_NORMAL -> "正常显示";
            case IamS1Constants.PROTECTION_HIDDEN -> "隐藏字段";
            case IamS1Constants.PROTECTION_MASKED -> "脱敏显示";
            default -> level;
        };
    }

    public static String maskPolicyName(String policy) {
        if (policy == null) {
            return null;
        }
        return MASK_POLICY_NAMES.getOrDefault(policy, policy);
    }

    public static String grantStatusName(String status) {
        if (status == null) {
            return null;
        }
        return IamS1Constants.DATA_GRANT_STATUS_ACTIVE.equals(status) ? "生效中" : "已撤销";
    }

    public static String operatorName(String operatorCode) {        if (operatorCode == null) {
            return null;
        }
        return OPERATOR_NAMES.getOrDefault(operatorCode, operatorCode);
    }

    public static String requestStatusName(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "PENDING" -> "待审批";
            case "APPROVED" -> "已批准";
            case "REJECTED" -> "已拒绝";
            case "WITHDRAWN" -> "已撤回";
            default -> status;
        };
    }

    public static String decisionName(String decision) {
        if (decision == null) {
            return null;
        }
        return "APPROVED".equals(decision) ? "同意" : "拒绝";
    }

    public static String rowScopeName(String rowScope) {
        if (rowScope == null) {
            return "全部记录";
        }
        return IamS1Constants.ROW_MATCH_ALL.equalsIgnoreCase(rowScope) ? "全部记录" : rowScope;
    }

    /** 授权来源：管理员直接配置，还是访问审批通过后生成。 */
    public static String grantSourceName(String grantSource) {
        if (grantSource == null) {
            return "管理员配置";
        }
        return switch (grantSource) {
            case "MANUAL" -> "管理员配置";
            case "APPROVAL" -> "访问审批通过";
            default -> grantSource;
        };
    }

    /** 把结构化条件值渲染为中文摘要；只用于管理端配置展示。 */
    public static String valueSummary(String operatorCode, String structuredValueJson) {
        if ("IS_NULL".equals(operatorCode) || "IS_NOT_NULL".equals(operatorCode)) {
            return operatorName(operatorCode);
        }
        if (structuredValueJson == null || structuredValueJson.isBlank()) {
            return "按服务端参数注入";
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(structuredValueJson);
            if (node.isArray()) {
                StringBuilder builder = new StringBuilder();
                for (JsonNode item : node) {
                    if (builder.length() > 0) {
                        builder.append("、");
                    }
                    builder.append(item.asText());
                }
                return builder.toString();
            }
            return node.asText();
        } catch (Exception exception) {
            return "条件值无法展示";
        }
    }

    /** 角色功能的中文能力摘要，例如“可以使用：使用问数、查看 SQL、导出结果”。 */
    public static String capabilitySummary(List<String> functionCodes) {
        if (functionCodes == null || functionCodes.isEmpty()) {
            return "未选择任何功能，无法进入任何业务工作区";
        }
        String names = functionCodes.stream()
                .map(IamS1FunctionCatalog::find)
                .filter(definition -> definition != null)
                .map(IamS1FunctionCatalog.Definition::name)
                .collect(Collectors.joining("、"));
        return names.isEmpty() ? "未选择任何功能，无法进入任何业务工作区" : "可以使用：" + names;
    }

    /** 授权的中文摘要，保存前与列表中都使用同一句式，让默认值可见。 */
    public static String grantSummary(String subjectType, String subjectName, String departmentScope,
                                      String effect, String datasourceName, String tableName,
                                      List<String> columns, List<String> conditionTexts, boolean longTerm,
                                      String validUntilText) {
        StringBuilder builder = new StringBuilder();
        String subject = (subjectName == null || subjectName.isBlank()) ? "该主体" : subjectName;
        if (IamS1Constants.SUBJECT_DEPARTMENT.equals(subjectType)
                && IamS1Constants.DEPARTMENT_SCOPE_INCLUDE_DESCENDANTS.equals(departmentScope)) {
            subject = subject + "及下级部门";
        }
        if (IamS1Constants.EFFECT_DENY.equals(effect)) {
            builder.append("禁止").append(subject).append("查询");
            builder.append(datasourceName == null ? "该数据源" : datasourceName);
            if (tableName == null) {
                builder.append("的全部数据");
            } else {
                builder.append("的 ").append(tableName).append(" 表");
            }
        } else {
            builder.append("允许").append(subject).append("查询");
            builder.append(datasourceName == null ? "该数据源" : datasourceName);
            if (tableName != null) {
                builder.append("的 ").append(tableName).append(" 表");
            }
            if (columns != null && !columns.isEmpty()) {
                builder.append("，只包含字段：").append(String.join("、", columns));
            }
            if (conditionTexts == null || conditionTexts.isEmpty()) {
                builder.append("，全部记录");
            } else {
                builder.append("，只看 ").append(String.join("，且 ", conditionTexts)).append(" 的记录");
            }
        }
        if (longTerm) {
            builder.append("，长期有效");
        } else if (validUntilText != null) {
            builder.append("，有效期至 ").append(validUntilText);
        }
        return builder.toString();
    }
}
