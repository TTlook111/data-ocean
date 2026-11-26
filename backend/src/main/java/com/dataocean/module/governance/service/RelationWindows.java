package com.dataocean.module.governance.service;

import com.dataocean.module.governance.entity.dto.RelationWindowDTO;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * 治理确认与问数共用日期窗口校验。历史文字只能提示需要核实。
 */
public final class RelationWindows {

    /**
     * 识别关联正文中需要可执行依据的条件线索，包括日期、业务状态和附加筛选。
     */
    private static final Pattern RESTRICTION =
            Pattern.compile("仅|只(?:适用|用于|允许|限|在|对|当|能|可|含|匹配|关联|保留|取)|限于|另限|不适用|不使用|不得"
                    + "|排除|剔除|不含|不计|必须|需要满足|开始适用|适用期|有效期|生效|失效|截止|截至|才能|才可|方可"
                    + "|(?:状态|标志|标识)\\s*(?:为|是|已|未|!=|<>|=)|(?i:\\b(?:where|only|except|unless)\\s+)"
                    + "|(?:<=|>=|<>|!=|=|<|>)\\s*(?:['\"]|[+-]?\\d|(?i:null|true|false)\\b)"
                    + "|(?<!\\d)\\d{4}\\s*[-/.]\\s*\\d{1,2}|[0-9〇零一二三四五六七八九]{4}\\s*年"
                    + "|[0-9一二三四五六七八九十]{1,3}\\s*月\\s*[0-9一二三四五六七八九十]{1,3}\\s*(?:日|号)");

    /**
     * 冗余日期说明只允许独立陈述已确认端点，不能连带忽略其他业务条件。
     */
    private static final Pattern START_NOTE =
            Pattern.compile("(?:自|从)?(\\d{4}-\\d{2}-\\d{2})(?:起(?:生效|适用)?|开始(?:生效|适用))(?:\\(含当日\\))?");

    /**
     * 截至说明中的日期必须与已确认的包含端点相同。
     */
    private static final Pattern END_NOTE = Pattern.compile("(?:截至|截止至)(\\d{4}-\\d{2}-\\d{2})(?:\\(含当日\\))?");

    /**
     * 完整区间说明只有在两端都匹配已确认窗口时才属于冗余文字。
     */
    private static final Pattern RANGE_NOTE =
            Pattern.compile("(?:适用期[:：])?(?:自|从)?(\\d{4}-\\d{2}-\\d{2})(?:至|到)(\\d{4}-\\d{2}-\\d{2})(?:\\(含起止日\\))?");

    /**
     * 各字段及分句独立检查，单独的日期注记不能使邻接句自动通过。
     */
    private static final Pattern CLAUSES = Pattern.compile("[\\r\\n。；;，,]+");

    /**
     * 历史无适用条件的关联沿用不限日期。
     */
    private static final Window UNRESTRICTED = new Window(null, null);

    /**
     * 日期窗口只接受无歧义的四位年及两位月日。
     */
    private static final Pattern ISO_DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * 确认后的含端点业务日期范围。
     *
     * @param validFrom 最早适用日，空值表示开放起点
     * @param validThrough 最晚适用日，空值表示开放终点
     */
    public record Window(LocalDate validFrom, LocalDate validThrough) {

        /**
         * 两端均开放时，执行方无需提供业务日期。
         */
        public boolean unrestricted() {
            return validFrom == null && validThrough == null;
        }
    }

    /**
     * 静态规则不维护可变状态。
     */
    private RelationWindows() {}

    /**
     * 治理和发布按同一适用口径校验已结构化的日期及仍留在正文里的条件。
     */
    public static Window usable(RelationWindowDTO input, String... text) {
        var window = input == null
                ? UNRESTRICTED
                : confirmed(input.getMode(), input.getValidFrom(), input.getValidThrough());
        return usable(window, text);
    }

    /**
     * 问数直接从确认事实校验同一规则，保留缺少适用限制的旧关系。
     */
    public static Window usable(JsonNode content) {
        var input = content.path("relationWindow");
        var window = input.isMissingNode() || input.isNull() ? UNRESTRICTED : confirmed(input);
        return usable(
                window,
                content.path("meaning").asText(""),
                content.path("rule").asText(""),
                content.path("usage").asText(""));
    }

    /**
     * 向语义复核提供保存的日期端点，供复核区分结构化日期与仍需处理的正文。
     */
    public static Window confirmed(JsonNode input) {
        return input.isObject()
                ? confirmed(
                        input.path("mode").asText(null),
                        input.path("validFrom").asText(null),
                        input.path("validThrough").asText(null))
                : null;
    }

    /**
     * 正文保留关联用途及数量关系，额外筛选须有可执行依据才可使用。
     */
    private static Window usable(Window window, String... text) {
        if (window == null) {
            // 结构化窗口不完整时，无法证明关联的适用范围。
            return null;
        }
        boolean unsupported = Arrays.stream(text)
                .filter(Objects::nonNull)
                .map(value -> Normalizer.normalize(value, Normalizer.Form.NFKC))
                .flatMap(CLAUSES::splitAsStream)
                .anyMatch(clause -> RESTRICTION.matcher(clause).find() && !matchesWindowNote(window, clause));
        return unsupported ? null : window;
    }

    /**
     * 只接受与结构化值完全相符的独立日期注记，执行日期始终从窗口读取。
     */
    private static boolean matchesWindowNote(Window window, String clause) {
        var note = clause.replaceAll("\\s+", "");
        if (window.unrestricted() && note.matches("(?:日期|时间)?适用期[:：]?(?:不限|无限制)")) {
            // 与明确的不限日期选择一致的短注记可以保留。
            return true;
        }
        var start = START_NOTE.matcher(note);
        var end = END_NOTE.matcher(note);
        var range = RANGE_NOTE.matcher(note);
        var from = Objects.toString(window.validFrom(), "");
        var through = Objects.toString(window.validThrough(), "");
        return start.matches() && from.equals(start.group(1))
                || end.matches() && through.equals(end.group(1))
                || range.matches() && from.equals(range.group(1)) && through.equals(range.group(2));
    }

    /**
     * 统一治理写入和问数读取语义。空值保留为开放端点。
     */
    private static Window confirmed(String mode, String from, String through) {
        if ("NONE".equals(mode) && from == null && through == null) {
            // 由业务确认人明确作出无限制的选择。
            return UNRESTRICTED;
        }
        if (!"DATE_RANGE".equals(mode)
                || from == null && through == null
                || from != null && !ISO_DAY.matcher(from).matches()
                || through != null && !ISO_DAY.matcher(through).matches()) {
            // 部分填写和非日期格式无法形成有效的运行窗口。
            return null;
        }
        try {
            // 日历解析同时验证闰年和月份日期。
            var start = from == null ? null : LocalDate.parse(from);
            var end = through == null ? null : LocalDate.parse(through);
            return start != null && end != null && end.isBefore(start) ? null : new Window(start, end);
        } catch (DateTimeParseException invalid) {
            // 格式正确但不在实际日历中的日期不能发布。
            return null;
        }
    }
}
