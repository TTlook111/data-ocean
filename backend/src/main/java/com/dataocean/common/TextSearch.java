package com.dataocean.common;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 规范化查询文本并转义 LIKE 通配符，保持字面搜索语义。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TextSearch {

    /**
     * 按 trim、NFKC 和固定 Locale 小写生成普通文本规范键。
     */
    public static String key(String value) {
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    /**
     * 将搜索文本转换为转义百分号、下划线和转义字符的字面模式。
     */
    public static String pattern(String value) {
        return "%" + key(value).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    /**
     * 按空白拆分搜索词并逐项转义，各项以交集匹配声明字段。
     */
    public static List<String> terms(String value) {
        return value == null || value.isBlank()
                ? List.of()
                : Arrays.stream(value.trim().split("\\s+"))
                        .map(TextSearch::pattern)
                        .toList();
    }
}
