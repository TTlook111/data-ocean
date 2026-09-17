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
    public static final String PROTECTION_NORMAL = "NORMAL";
    public static final String PROTECTION_HIDDEN = "HIDDEN";
    public static final String PROTECTION_MASKED = "MASKED";
    public static final String ROW_MATCH_ALL = "ALL";
    public static final String ROW_MATCH_ANY = "ANY";
    public static final String PERMISSION_CACHE_PREFIX = "iam-s1:permission:";
    public static final String PERMISSION_CACHE_INDEX_PREFIX = "iam-s1:permission:index:";
    public static final int ENABLED = 1;
    public static final int DISABLED = 0;

    private IamS1Constants() {
    }
}
