package com.dataocean.module.governance.service;

import com.dataocean.module.governance.entity.dto.MetricFilterDTO;
import com.dataocean.module.governance.entity.dto.MetricRulesDTO;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 指标确认和问数共用的可执行结构约束，由各自调用者提供权威字段类型。
 */
public final class MetricRules {

    /**
     * 币种和文本等值筛选可使用的原生列类型。
     */
    private static final Pattern TEXT = Pattern.compile("(?i)(char|varchar|character|text|enum).*");

    /**
     * 固定等值条件可使用的原生数值类型。
     */
    private static final Pattern NUMBER =
            Pattern.compile("(?i)(tinyint|smallint|mediumint|int|integer|bigint|decimal|numeric|double|float|real).*");

    /**
     * 指标单位沿用结果显示可交付的有限字符范围。
     */
    private static final Pattern UNIT = Pattern.compile("[\\p{L}\\p{N} ./_%²³-]{1,32}");

    /**
     * 仅提供共享的口径校验。
     */
    private MetricRules() {}

    /**
     * 草稿可以不完整，确认和执行必须明确三类规则且全部引用可核验列。
     */
    public static boolean complete(MetricRulesDTO rules, Function<String, String> type) {
        if (rules == null
                || rules.getDateMode() == null
                || rules.getFixedFilters() == null
                || rules.getUnitMode() == null
                || rules.getFixedFilters().size() > 8) {
            // 未填写不能当作已确认无约束。
            return false;
        }
        boolean date =
                switch (rules.getDateMode()) {
                    case "NONE" -> absent(rules.getDateFieldId());
                    case "BUSINESS_DAY" -> !absent(rules.getDateFieldId())
                            && "date".equalsIgnoreCase(type.apply(rules.getDateFieldId()));
                    default -> false;
                };
        boolean unit =
                switch (rules.getUnitMode()) {
                    case "NONE" -> absent(rules.getUnit()) && absent(rules.getUnitFieldId());
                    case "FIXED" -> validUnit(rules.getUnit()) && absent(rules.getUnitFieldId());
                    case "COLUMN" -> absent(rules.getUnit())
                            && !absent(rules.getUnitFieldId())
                            && TEXT.matcher(Objects.toString(type.apply(rules.getUnitFieldId()), ""))
                                    .matches();
                    default -> false;
                };
        var filters = rules.getFixedFilters();
        return date
                && unit
                && filters.stream().allMatch(filter -> validFilter(filter, type))
                && filters.stream().map(MetricFilterDTO::getFieldId).distinct().count() == filters.size();
    }

    /**
     * AI 候选中的执行规则字段随原公式引用一起固定治理依赖。
     */
    public static Set<String> fieldIds(MetricRulesDTO rules) {
        var fields = new LinkedHashSet<String>();
        if (rules != null) {
            // 允许草稿暂未填写日期、币种或固定条件。
            fields.add(rules.getDateFieldId());
            fields.add(rules.getUnitFieldId());
            if (rules.getFixedFilters() != null) {
                rules.getFixedFilters().stream()
                        .filter(Objects::nonNull)
                        .map(MetricFilterDTO::getFieldId)
                        .forEach(fields::add);
            }
        }
        fields.removeIf(MetricRules::absent);
        return fields;
    }

    /**
     * 固定条件只接受有类型的数值或文本，业务日期由专属规则控制。
     */
    private static boolean validFilter(MetricFilterDTO filter, Function<String, String> type) {
        if (filter == null
                || absent(filter.getFieldId())
                || absent(filter.getValue())
                || filter.getValue().length() > 120) {
            return false;
        }
        var nativeType = Objects.toString(type.apply(filter.getFieldId()), "");
        if (TEXT.matcher(nativeType).matches()) {
            return true;
        }
        if (!numeric(nativeType)) {
            return false;
        }
        try {
            // 与查询阶段的数值绑定约束一致，不能把不可绑定的值写成确认规则。
            var value = new BigDecimal(filter.getValue()).stripTrailingZeros();
            return value.precision() <= 65 && Math.abs((long) value.scale()) <= 65;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    /**
     * 汇总及数值等值绑定使用实际目录的数值类型。
     */
    public static boolean numeric(String type) {
        return type != null && NUMBER.matcher(type).matches();
    }

    /**
     * 治理固定值和来源读取的单位必须满足相同的交付格式，空白属于缺失。
     */
    public static boolean validUnit(String unit) {
        return !absent(unit) && UNIT.matcher(unit).matches();
    }

    /**
     * 草稿缺省和空输入统一表示未填写。
     */
    private static boolean absent(String value) {
        return value == null || value.isBlank();
    }
}
