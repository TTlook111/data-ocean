package com.dataocean.module.governance.service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 治理确认与只读编译共享的纯计算公式，不接收筛选或 SQL 片段。
 *
 * @param operation 受支持的聚合运算
 * @param fields 运算依次引用的物理字段，保留可选的模式限定
 */
public record MetricFormula(String operation, List<String> fields) {

    /**
     * 字段须带表名。允许用模式区分同名表。
     */
    private static final String FIELD = "[\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*){1,2}";

    /**
     * 只识别当前编译器支持的运算，要求匹配整段公式。
     */
    private static final Pattern FORMULA = Pattern.compile(
            "SUM\\s*\\(\\s*(" + FIELD + ")(?:\\s*-\\s*(" + FIELD + "))?\\s*\\)" + "|COUNT\\s*\\(\\s*DISTINCT\\s+("
                    + FIELD + ")\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 解析固定运算和字段。无法执行的正文留在草稿供人工修订。
     */
    public static MetricFormula parse(String text) {
        if (text == null) {
            // 尚未填写公式的草稿没有可校验的运算。
            return null;
        }
        var matcher = FORMULA.matcher(text.strip());
        if (!matcher.matches()) {
            // 公式内的说明、条件或未知运算不能进入已确认口径。
            return null;
        }
        if (matcher.group(3) != null) {
            // 去重计数只引用一个有技术依据的键。
            return new MetricFormula("COUNT_DISTINCT", List.of(matcher.group(3)));
        }
        return matcher.group(2) == null
                ? new MetricFormula("SUM", List.of(matcher.group(1)))
                : new MetricFormula("SUM_DIFFERENCE", List.of(matcher.group(1), matcher.group(2)));
    }

    /**
     * 仅允许相同的表列身份。带模式的引用不可以匹配另一模式的同名表。
     */
    public static boolean references(String reference, String qualifiedField) {
        return reference.equalsIgnoreCase(qualifiedField)
                || reference.indexOf('.') == reference.lastIndexOf('.')
                        && qualifiedField
                                .substring(qualifiedField.indexOf('.') + 1)
                                .equalsIgnoreCase(reference);
    }
}
