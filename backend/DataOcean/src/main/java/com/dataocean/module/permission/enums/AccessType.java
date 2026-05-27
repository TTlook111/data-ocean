package com.dataocean.module.permission.enums;

/**
 * 访问类型枚举
 * <p>
 * 定义行列级策略中的访问控制类型。
 * </p>
 *
 * @author dataocean
 */
public enum AccessType {

    /** 允许访问 */
    ALLOW,
    /** 禁止访问 */
    DENY,
    /** 脱敏访问 */
    MASK
}
