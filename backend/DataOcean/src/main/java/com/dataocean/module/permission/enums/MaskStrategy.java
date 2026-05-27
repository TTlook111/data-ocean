package com.dataocean.module.permission.enums;

import lombok.Getter;

/**
 * 数据脱敏策略枚举
 * <p>
 * 定义各类敏感字段的脱敏规则，每种策略对应一种数据类型的脱敏方式。
 * </p>
 *
 * @author dataocean
 */
@Getter
public enum MaskStrategy {

    /** 手机号脱敏：保留前3后4，如 138****5678 */
    PHONE("手机号", 3, 4),
    /** 身份证脱敏：保留前4后4，如 3101**********1234 */
    ID_CARD("身份证", 4, 4),
    /** 邮箱脱敏：保留前3 + 域名，如 zha***@example.com */
    EMAIL("邮箱", 3, 0),
    /** 银行卡脱敏：仅保留后4，如 ****5678 */
    BANK_CARD("银行卡", 0, 4),
    /** 姓名脱敏：保留姓，如 张* */
    NAME("姓名", 1, 0);

    /** 策略描述 */
    private final String description;
    /** 保留前缀长度 */
    private final int prefixKeep;
    /** 保留后缀长度 */
    private final int suffixKeep;

    MaskStrategy(String description, int prefixKeep, int suffixKeep) {
        this.description = description;
        this.prefixKeep = prefixKeep;
        this.suffixKeep = suffixKeep;
    }
}
