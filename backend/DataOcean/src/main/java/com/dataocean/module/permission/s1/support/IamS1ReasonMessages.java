package com.dataocean.module.permission.s1.support;

import java.util.Map;

/**
 * IAM-SIMPLE-1 稳定原因码到中文提示的唯一映射。
 * <p>
 * 原因码来自 {@code IamS1AuthorizationResolver} 与数据授权 Resolver，前端不得自行拼接权限结论；
 * 无权限时必须给出明确中文提示，不静默返回空数据。
 * </p>
 */
public final class IamS1ReasonMessages {

    private static final Map<String, String> REASON_MESSAGES = Map.ofEntries(
            Map.entry("ALLOWED", "允许"),
            Map.entry("MISSING_REQUIRED_PARAMETER", "缺少必要参数，无法判定 IAM-SIMPLE-1 权限"),
            Map.entry("USER_NOT_ENABLED", "账号不存在或已禁用"),
            Map.entry("UNKNOWN_FUNCTION", "未知的 IAM-SIMPLE-1 功能"),
            Map.entry("DATASOURCE_NOT_ENABLED", "数据源不存在或未启用"),
            Map.entry("NO_S1_BINDING", "当前账号没有任何可用的 IAM-SIMPLE-1 角色绑定"),
            Map.entry("FUNCTION_NOT_GRANTED", "当前 IAM-SIMPLE-1 角色不包含该功能"),
            Map.entry("DATASOURCE_NOT_ASSIGNED", "当前 IAM-SIMPLE-1 角色不负责该数据源"),
            Map.entry("SAME_BINDING_REQUIRED", "该功能与数据源负责范围不在同一个角色绑定上"),
            Map.entry("PROTOCOL_MISMATCH", "请求不是 IAM-SIMPLE-1 协议"),
            Map.entry("EMPTY_RESOURCE", "没有配置可查询的表和字段"),
            Map.entry("SNAPSHOT_NOT_PUBLISHED", "元数据快照不存在或未发布"),
            Map.entry("NO_ALLOW_GRANT", "没有任何允许的数据授权"),
            Map.entry("DENIED_BY_RULE", "命中禁止规则"),
            Map.entry("MASK_POLICY_CONFLICT", "同一输出列存在多种脱敏策略，已拒绝返回")
    );

    private IamS1ReasonMessages() {
    }

    public static String describe(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return "未提供原因";
        }
        return REASON_MESSAGES.getOrDefault(reasonCode, "未识别的 IAM-SIMPLE-1 原因码：" + reasonCode);
    }

    public static String adminDenyMessage(String functionName, String reasonCode) {
        return "没有" + (functionName == null ? "该后台功能" : "“" + functionName + "”")
                + "权限：" + describe(reasonCode);
    }
}
