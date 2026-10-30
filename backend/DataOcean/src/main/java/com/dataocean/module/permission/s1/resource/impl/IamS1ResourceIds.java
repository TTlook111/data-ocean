package com.dataocean.module.permission.s1.resource.impl;

/**
 * 资源 ID 规范化：把受限 SpEL 解析出来的值收敛成 Long。
 *
 * <p>类型不符一律返回 null，由调用方 fail-closed，而不是抛 {@code ClassCastException}
 * 或者把字符串硬转成 ID。</p>
 */
final class IamS1ResourceIds {

    private IamS1ResourceIds() {
    }

    static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Integer intValue) {
            return intValue.longValue();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return Long.valueOf(trimmed);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }
}
