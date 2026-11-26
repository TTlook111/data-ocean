package com.dataocean.module.query.service;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 把含义唯一的日常日期说法换算为提问时区内的单个业务日。
 */
final class QueryDates {

    /**
     * 已经声明了日历日期或月份时，不用相对词覆盖该日期。
     */
    private static final Pattern CALENDAR_DATE =
            Pattern.compile("(?:[0-9]{4}[-/.][0-9]{1,2}|[0-9]{1,2}[-/][0-9]{1,2}(?![0-9])|"
                    + "[0-9一二三四五六七八九十]{1,3}\\s*月(?!奶)|[本上这下](?:个)?月)");

    /**
     * 只接受完整的日历日期，避开编码中的数字片段。
     */
    private static final Pattern FULL_DATE =
            Pattern.compile("(?<![A-Za-z0-9_./-])(?:([0-9]{4})([-/.])([0-9]{1,2})\\2([0-9]{1,2})"
                    + "|([0-9]{4})\\s*年\\s*([0-9]{1,2})\\s*月\\s*([0-9]{1,2})\\s*[日号])"
                    + "(?![A-Za-z0-9_/-]|\\.[0-9])");

    /**
     * 日号标记不包含仓库、门店或商品编号。
     */
    private static final String DAY_SUFFIX = "(?:号(?!\\s*(?:仓|店|门店|库|商品|柜|车))|日)";

    /**
     * 只有日号时如果缺少月份和对话依据，需要澄清；仓库等序号不属于日期。
     */
    private static final Pattern DAY_ONLY = Pattern.compile(
            "(?<![\\w-])(?:[1-9]|[12][0-9]|3[01]|[一二三]?十[一二三四五六七八九]?|[一二三四五六七八九])" + "\\s*" + DAY_SUFFIX);

    /**
     * 澄清答“这个月”后，用原日号绑定具体日期。
     */
    private static final Pattern THIS_MONTH = Pattern.compile("(?:这(?:个)?月|本月)");

    /**
     * 原问明确给出的阿拉伯数字日号。
     */
    private static final Pattern NUMBERED_DAY = Pattern.compile("(?<![\\w-])([1-9]|[12][0-9]|3[01])\\s*" + DAY_SUFFIX);

    /**
     * 优先匹配较长的相对日表达，星期分支使用明确的自然周。
     */
    private static final Pattern RELATIVE_DAY =
            Pattern.compile("(?iu)大前天|前天|昨天|昨日|今天|今日|明天|明日|后天|\\b(?:the\\s+)?day before yesterday\\b|"
                    + "\\btwo days ago\\b|\\b(?:yesterday|today|tomorrow)\\b|"
                    + "(?:上|本|这)(?:周|星期)[一二三四五六日天]");

    /**
     * “截至”及“从某天到某天”表达的是区间或边界，不能当作单日结果。
     */
    private static final Pattern PERIOD =
            Pattern.compile("截至|从.{0,32}(?:至|到)|同比|环比|对比|比较|相比|(?i:\\b(?:since|until|through)\\b)");

    /**
     * 排除、示例和边界说明中的日期不能直接确定等值条件。
     */
    private static final Pattern NON_POINT_DATE = Pattern.compile(
            "(?i)不查|不要|排除|不含|不包括|以前|以后|之前|之后|以外|之外|示例|例如|比如" + "|\\b(?:not|except|excluding|before|after)\\b");

    /** 只识别独立改变时间到上一个自然周的短追问。 */
    private static final Pattern PREVIOUS_WEEK_FOLLOWUP =
            Pattern.compile("^\\s*(?:那(?:么)?\\s*)?上(?:周|个星期)(?:的)?(?:呢)?[？?。.!！\\s]*$");

    /** 明确只改变自然周，其余语义由当前已确认口径决定。 */
    static boolean previousWeekFollowup(String question) {
        return PREVIOUS_WEEK_FOLLOWUP.matcher(question).matches();
    }

    /** 原请求接受日所在自然周之前的周一，周日由调用者按七日计算。 */
    static LocalDate previousMonday(LocalDate anchor) {
        return anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
    }

    /**
     * 仅含日号，没有判断月份的用户表达。
     */
    static boolean needsMonth(String question) {
        return DAY_ONLY.matcher(question).find()
                && !CALENDAR_DATE.matcher(question).find();
    }

    /**
     * 通用规划负责其余日期表达，短路径只使用可以唯一确定的日期。
     */
    static boolean mentionsSpecificDate(String question) {
        return CALENDAR_DATE.matcher(question).find()
                || DAY_ONLY.matcher(question).find();
    }

    /**
     * 从请求原话解析能独立确定的单日，补充中的时间选择优先。
     */
    static LocalDate requestedDay(String question, String supplement, LocalDate anchor) {
        if (supplement == null) {
            // 首次请求仅使用用户原话。
            return requestedDay(question, anchor);
        }
        var supplied = requestedDay(supplement, anchor);
        if (supplied != null
                || !THIS_MONTH.matcher(supplement).find()
                        && (mentionsSpecificDate(supplement)
                                || RELATIVE_DAY.matcher(supplement).find())) {
            // 已补充独立的日期表达，不能再回退到原来的日期。
            return supplied;
        }
        return requestedDay(question + "\n补充：" + supplement, anchor);
    }

    /**
     * 只确认完整、唯一的日历日或既有可计算的相对日期。
     */
    static LocalDate requestedDay(String question, LocalDate anchor) {
        var exact = FULL_DATE.matcher(question);
        if (!exact.find()) {
            // 保留相对日及补充月份的既有处理。
            return singleRelativeDay(question, anchor);
        }
        boolean numeric = exact.group(1) != null;
        int year = Integer.parseInt(exact.group(numeric ? 1 : 5));
        int month = Integer.parseInt(exact.group(numeric ? 3 : 6));
        int day = Integer.parseInt(exact.group(numeric ? 4 : 7));
        if (exact.find()
                || PERIOD.matcher(question).find()
                || NON_POINT_DATE.matcher(question).find()
                || RELATIVE_DAY.matcher(question).find()) {
            // 多个日期或区间继续由语义复核处理，不能声明是已确认的单日。
            return null;
        }
        try {
            // 由日历校验实际年月日，防止错误日期变成数据库隐式转换。
            return LocalDate.of(year, month, day);
        } catch (DateTimeException invalid) {
            throw QueryFailure.knowledge();
        }
    }

    /**
     * 对同时提及多个时间的比较、范围或混写日期交给完整的意图核对。
     */
    static LocalDate singleRelativeDay(String question, LocalDate anchor) {
        if (PERIOD.matcher(question).find()) {
            // 仍需单独理解范围及比较。
            return null;
        }
        var dayNumber = NUMBERED_DAY.matcher(question);
        if (THIS_MONTH.matcher(question).find()
                && !CALENDAR_DATE
                        .matcher(THIS_MONTH.matcher(question).replaceAll(""))
                        .find()
                && dayNumber.find()) {
            int day = Integer.parseInt(dayNumber.group(1));
            if (!dayNumber.find() && day <= anchor.lengthOfMonth()) {
                // 保留提问时的年月，只替换用户给出的日号。
                return anchor.withDayOfMonth(day);
            }
        }
        var match = RELATIVE_DAY.matcher(question);
        if (CALENDAR_DATE.matcher(question).find() || DAY_ONLY.matcher(question).find() || !match.find()) {
            // 无日期或已有具体日期时不加入新的时间条件。
            return null;
        }
        String relative = match.group().toLowerCase(Locale.ROOT);
        if (match.find()) {
            // 多个时间表达不压缩成单日。
            return null;
        }
        return switch (relative) {
            case "大前天" -> anchor.minusDays(3);
            case "前天", "day before yesterday", "the day before yesterday", "two days ago" -> anchor.minusDays(2);
            case "昨天", "昨日", "yesterday" -> anchor.minusDays(1);
            case "今天", "今日", "today" -> anchor;
            case "明天", "明日", "tomorrow" -> anchor.plusDays(1);
            case "后天" -> anchor.plusDays(2);
            default -> {
                // 周一是本自然周起点，周日保留第七天。
                int index = "一二三四五六日天".indexOf(relative.charAt(relative.length() - 1));
                LocalDate monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield monday.plusDays(Math.min(index, 6) - (relative.startsWith("上") ? 7 : 0));
            }
        };
    }

    /**
     * 日历计算只依赖请求中已经固定的日期。
     */
    private QueryDates() {}
}
