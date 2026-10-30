package com.dataocean.module.permission.s1;

/**
 * IAM-SIMPLE-1 的固定协议和安全常量。
 */
public final class IamS1Constants {

    public static final String PROTOCOL_VERSION = "IAM-SIMPLE-1";
    public static final String SYSTEM_ADMIN_ROLE_CODE = "IAM_S1_SYSTEM_ADMIN";
    public static final String FUNCTION_STATUS_ACTIVE = "ACTIVE";
    public static final String DATA_GRANT_STATUS_ACTIVE = "ACTIVE";
    public static final String DATA_GRANT_STATUS_REVOKED = "REVOKED";
    public static final String SUBJECT_USER = "USER";
    public static final String SUBJECT_ROLE = "ROLE";
    public static final String SUBJECT_DEPARTMENT = "DEPARTMENT";
    public static final String DEPARTMENT_SCOPE_SELF = "SELF";
    public static final String DEPARTMENT_SCOPE_INCLUDE_DESCENDANTS = "INCLUDE_DESCENDANTS";
    public static final String RESOURCE_SCOPE_DATASOURCE = "DATASOURCE";
    public static final String RESOURCE_SCOPE_TABLE = "TABLE";
    public static final String EFFECT_ALLOW = "ALLOW";
    public static final String EFFECT_DENY = "DENY";
    public static final String GRANT_SOURCE_MANUAL = "MANUAL";
    /** B4：访问审批通过后生成的个人临时授权来源。 */
    public static final String GRANT_SOURCE_APPROVAL = "APPROVAL";
    public static final String PROTECTION_NORMAL = "NORMAL";
    public static final String PROTECTION_HIDDEN = "HIDDEN";
    public static final String PROTECTION_MASKED = "MASKED";
    public static final String ROW_MATCH_ALL = "ALL";
    public static final String ROW_MATCH_ANY = "ANY";
    public static final String PERMISSION_CACHE_PREFIX = "iam-s1:permission:";
    public static final String PERMISSION_CACHE_INDEX_PREFIX = "iam-s1:permission:index:";
    public static final int ENABLED = 1;
    public static final int DISABLED = 0;

    /**
     * 字段保护等级的严格程度，数值越大越严格。
     *
     * <p>**未知等级按最严格的 HIDDEN 处理（fail-closed）**：展示层的“字段是否可选/可授权”
     * 必须与统一 Resolver 的 `mergeProtection` 用同一套排序，否则页面会把一个真实查询时
     * 会被隐藏的字段显示成“正常可用”。</p>
     */
    public static int protectionRank(String level) {
        if (PROTECTION_HIDDEN.equals(level)) {
            return 3;
        }
        if (PROTECTION_MASKED.equals(level)) {
            return 2;
        }
        if (PROTECTION_NORMAL.equals(level)) {
            return 1;
        }
        return 3;
    }

    /**
     * 取两个保护等级中更严格的一个，并把未知等级规范化为 HIDDEN。
     *
     * <p>不要用 `order.indexOf(level)` 比较：未知等级会得到 `-1`，与 NORMAL / MASKED 比较时
     * 结果不可靠——例如未知等级会被判成比 NORMAL 更宽松，从而在页面上放行一个应当隐藏的字段。</p>
     */
    public static String stricterProtection(String left, String right) {
        int strongest = Math.max(protectionRank(left), protectionRank(right));
        return switch (strongest) {
            case 3 -> PROTECTION_HIDDEN;
            case 2 -> PROTECTION_MASKED;
            default -> PROTECTION_NORMAL;
        };
    }

    private IamS1Constants() {
    }
}
