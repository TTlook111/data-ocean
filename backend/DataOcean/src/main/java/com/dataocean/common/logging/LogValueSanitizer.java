package com.dataocean.common.logging;

import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 日志值脱敏工具。
 *
 * <p>日志参数通常来自 HTTP 请求，无法可靠判断一个普通字符串是否为密码、令牌或密钥，
 * 因此字符串和不透明对象默认不输出原始值。Map 与 Collection 会递归处理，敏感字段始终显示为 {@code ***}。</p>
 */
final class LogValueSanitizer {

    private LogValueSanitizer() {
    }

    static String summarize(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence || value instanceof Character) {
            return "***";
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?> ||
                value instanceof UUID || value instanceof TemporalAccessor) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> String.valueOf(entry.getKey()) + "=" +
                            (isSensitiveKey(entry.getKey()) ? "***" : summarize(entry.getValue())))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(LogValueSanitizer::summarize)
                    .collect(Collectors.joining(", ", "[", "]"));
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    result.append(", ");
                }
                result.append(summarize(Array.get(value, i)));
            }
            return result.append(']').toString();
        }
        // DTO、实体和异常等对象的 toString() 可能包含敏感字段，统一只记录类型。
        return '<' + value.getClass().getSimpleName() + '>';
    }

    private static boolean isSensitiveKey(Object key) {
        if (key == null) {
            return false;
        }
        String normalized = String.valueOf(key).replace("-", "").replace("_", "").toLowerCase();
        return normalized.contains("password") || normalized.contains("passwd") ||
                normalized.contains("token") || normalized.contains("secret") ||
                normalized.contains("apikey") || normalized.contains("authorization") ||
                normalized.contains("credential");
    }
}
