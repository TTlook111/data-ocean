package com.dataocean.common.util;

/**
 * SQL 标识符校验工具。
 *
 * <p>仅允许常规业务表/列名使用的 Unicode 字母、数字、下划线和 {@code $}，
 * 并限制为 MySQL 标识符最大长度。调用方仍需使用反引号包裹返回值。</p>
 */
public final class SqlIdentifierValidator {

    private static final int MAX_IDENTIFIER_LENGTH = 64;
    private static final String IDENTIFIER_PATTERN = "[\\p{L}\\p{N}_$]+";

    private SqlIdentifierValidator() {
    }

    public static String validate(String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.length() > MAX_IDENTIFIER_LENGTH ||
                !identifier.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException("SQL 标识符格式不合法");
        }
        return identifier;
    }
}
