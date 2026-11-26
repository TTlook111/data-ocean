package com.dataocean.module.governance.service;

import com.dataocean.module.governance.entity.dto.RelationWindowDTO;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * 治理确认与问数共用日期窗口校验。历史文字只能提示需要核实。
 */
public final class RelationWindows {

    /**
     * 检测未结构化的历史适用限制，不从正文推算日期或适用性。
     */
    private static final Pattern LEGACY_RESTRICTION = Pattern.compile(
            "[仅只](?:适用|用于|允许|限|在|对|当|能|可)|不适用|不使用|不得|开始适用|适用期|有效期|生效|失效|截止|截至|才能|才可|方可"
                    + "|(?<!\\d)\\d{4}\\s*[-/.]\\s*\\d{1,2}|[0-9〇零一二三四五六七八九]{4}\\s*年"
                    + "|[0-9一二三四五六七八九十]{1,3}\\s*月\\s*[0-9一二三四五六七八九十]{1,3}\\s*(?:日|号)");

    /**
     * 日期窗口只接受无歧义的四位年及两位月日。
     */
    private static final Pattern ISO_DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * 确认后的含端点业务日期范围，两个端点皆空表示不限。
     */
    public record Window(LocalDate validFrom, LocalDate validThrough) {}

    /**
     * 静态规则不维护可变状态。
     */
    private RelationWindows() {}

    /**
     * 旧关系中发现限制时需要业务人员确认结构化规则。
     */
    public static boolean requiresConfirmation(String rule, String usage) {
        return LEGACY_RESTRICTION.matcher(Normalizer.normalize(rule + "\n" + usage, Normalizer.Form.NFKC)).find();
    }

    /**
     * 确认时要求完整字段、有效日历日期和正确的区间顺序。
     */
    public static Window confirmed(RelationWindowDTO input) {
        return input == null ? null : confirmed(input.getMode(), input.getValidFrom(), input.getValidThrough());
    }

    /**
     * 查询只读取来自关系确认修订的结构化值。
     */
    public static Window confirmed(JsonNode input) {
        return input.isObject() ? confirmed(input.path("mode").asText(null),
                input.path("validFrom").asText(null), input.path("validThrough").asText(null)) : null;
    }

    /**
     * 统一治理写入和问数读取语义。空值保留为开放端点。
     */
    private static Window confirmed(String mode, String from, String through) {
        if ("NONE".equals(mode) && from == null && through == null) {
            // 由业务确认人明确作出无限制的选择。
            return new Window(null, null);
        }
        if (!"DATE_RANGE".equals(mode) || from == null && through == null
                || from != null && !ISO_DAY.matcher(from).matches()
                || through != null && !ISO_DAY.matcher(through).matches()) {
            return null;
        }
        try {
            var start = from == null ? null : LocalDate.parse(from);
            var end = through == null ? null : LocalDate.parse(through);
            return start != null && end != null && end.isBefore(start) ? null : new Window(start, end);
        } catch (DateTimeParseException invalid) {
            // 格式正确但不在实际日历中的日期不能发布。
            return null;
        }
    }
}
