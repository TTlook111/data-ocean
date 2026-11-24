package com.dataocean.module.query.service;

import com.dataocean.common.Ids;
import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
import com.dataocean.module.query.entity.vo.QueryColumnVO;
import com.dataocean.module.query.entity.vo.QuerySeriesWindowVO;
import com.dataocean.module.query.service.QueryCorpus.Basis;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.Plan;
import com.dataocean.module.query.service.QueryProtocol.TimeSelection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;

/**
 * 把经治理的有限关系和运算编译为参数化只读 SQL，任何模型文本都不成为 SQL。
 */
final class QuerySql {

    /** 输出列、实际血缘和参数固定后才允许派发。 */
    record Compiled(
            String sql,
            List<Object> parameters,
            List<QueryColumnVO> columns,
            List<Field> projection,
            String unit,
            String scope,
            boolean verifiesUnit,
            QuerySeriesWindowVO series,
            QueryUsage usage) {}

    /** 关联由同版治理的确切字段对提供。 */
    private record Pair(Field left, Field right) {}

    /** 关联组的全部键都要参与实际 JOIN。 */
    private record Relation(String left, String right, List<Pair> keys, GovernanceFactVO fact) {}

    /** 标准聚合字段数，其他运算一律不接受。 */
    private static final Map<String, Integer> ARITY = Map.of("COUNT_DISTINCT", 1, "SUM", 1, "SUM_DIFFERENCE", 2);

    /**
     * 指标文字明确要求同一单位时，需要一条可核实的计量字段。
     */
    private static final Pattern SAME_UNIT = Pattern.compile("单位(?:必须|须|应)一致|不能混加[^。；]{0,8}单位|不同单位[^。；]{0,5}不能混加");

    /** 核对原话是否允许采用具体日期，日期值仍由独立语义节点核对。 */
    private static final Pattern REQUESTED_DATE = Pattern.compile(
            """
            (?ix)
            (?<!\\d)\\d{4}[-/.]\\d{1,2}(?:[-/.]\\d{1,2})?(?!\\d)
            | (?<![\\w-])\\d{1,2}[-/]\\d{1,2}(?![\\d-])
            | (?<![\\w-])[0-9０-９〇零一二两三四五六七八九十]{1,4}\\s*(?:年|月(?!奶)|日|号(?!仓|门店|店|库))
            | 昨[天日]|前天|[前去]年|明[天日]|后天|[上下这本]周|[上下这]个星期|[上下这本](?:个)?月|[上下这本]季|本年|今年|[月年][初末]|月底|季度
            | (?:周|星期)[一二三四五六日天]
            | (?:最近|过去|近|前|后|接下来)\\s*[零一二两三四五六七八九十百\\d]+\\s*(?:天|日|周|个?月|年)
            | yesterday|tomorrow|\\b(?:last|next|this|past)\\s+(?:\\d+\\s+)?(?:days?|weeks?|months?|years?)\\b
            """);

    /** 沿用旧日期需要本题明示，并且冻结上下文中有已验证轮次。 */
    private static final Pattern REFERENCED_DATE =
            Pattern.compile("(?:同一|相同|原|之前的|上次的|刚才的)(?:天|日期|业务日|时间段|日期区间)|时间不变|日期不变|还是那天");

    /**
     * 已确认只修改部分条件时，日期也属于原来条件；本轮有新时间要求则新时间优先。
     */
    private static final Pattern UNCHANGED_CONDITIONS = Pattern.compile("(?:其他|其余|剩下的)条件(?:照旧|沿用|一样|不变)");

    /**
     * 明确当前时间的表达不再沿用上轮日期。
     */
    private static final Pattern CURRENT_DAY = Pattern.compile("今天|今日|当天|本日|现在|当前|实时");

    /**
     * 业务治理标注的商品标识列，需要核对输入的编码出处。
     */
    private static final Pattern PRODUCT_CODE_LABEL = Pattern.compile("(?i)\\bSKU\\b.{0,10}(?:编码|代码|code)");

    /**
     * 商品主键不能由商品名称或从业务码的数字部分猜出。
     */
    private static final Pattern PRODUCT_ID_LABEL = Pattern.compile("(?i)\\bSKU\\b.{0,10}(?:主键|\\bid\\b)");

    /**
     * 数值标识需要用户明确以主键身份说明。
     */
    private static final Pattern EXPLICIT_IDENTIFIER = Pattern.compile("(?i)主键|\\bid\\b");

    /**
     * 有明确的上文指代时才沿用已经确认的商品标识。
     */
    private static final Pattern PRODUCT_CONTINUATION =
            Pattern.compile("这个|那个|这款|那款|那件|同一|同款|同样|继续|刚才|上一轮|原来|[前后上下][一1]天");

    /**
     * 只转换已确认的两字段差额合计，其他公式仍走正常规划。
     */
    private static final Pattern PUBLISHED_STOCK_FORMULA =
            Pattern.compile("(?i)sum\\(([\\w]+)\\.([\\w]+)-([\\w]+)\\.([\\w]+)\\)");

    /** 两列求和提议仍要通过既有相减指标的逐字段验证和独立语义核对。 */
    static Plan normalize(Plan plan) {
        if ("SUM".equals(plan.operation())
                && plan.fields() != null
                && plan.fields().size() == 2) {
            return new Plan(
                    plan.decision(),
                    plan.clarification(),
                    plan.binding(),
                    "SUM_DIFFERENCE",
                    plan.metric(),
                    plan.unit(),
                    plan.fields(),
                    plan.filters(),
                    plan.time());
        }
        return plan;
    }

    /** 仅为已确认需要业务日期的指标补默认值，显式筛选沿用原条件。 */
    Plan resolveTime(Basis basis, Plan plan, LocalDate today, String question, boolean hasHistory) {
        if (plan.operation() == null
                || plan.filters() == null
                || plan.filters().stream()
                        .anyMatch(filter -> filter == null || filter.field() == null || filter.value() == null)) {
            throw QueryFailure.knowledge();
        }
        var businessDay = businessDate(basis, plan);
        var requestedDay = QueryDates.singleRelativeDay(question, today);
        var time = plan.time();
        if (requestedDay != null && (time == null || "DAY".equals(time.mode()))) {
            // 绑定用户口语中唯一的日历日期，覆盖模型从材料示例复制的日期。
            var date = time == null ? businessDay : time.field();
            if (date != null && dateField(basis, date)) {
                var filters = plan.filters().stream()
                        .filter(filter -> !date.equals(filter.field()))
                        .toList();
                String day = requestedDay.toString();
                return withTime(plan, filters, new TimeSelection(date, "DAY", day, day, false));
            }
        }
        if (!REQUESTED_DATE.matcher(question).find()
                && !(hasHistory && REFERENCED_DATE.matcher(question).find())) {
            // 无日期时仅给确需业务日期的单值补当日。按跨日定义统计的指标保持原范围。
            var filters = plan.filters().stream()
                    .filter(filter -> !dateField(basis, filter.field()))
                    .toList();
            var selection = time != null && "TREND".equals(time.mode())
                    ? new TimeSelection(time.field(), "TREND", null, null, null)
                    : businessDay == null ? null : new TimeSelection(businessDay, "DAY", null, null, null);
            plan = withTime(plan, filters, selection);
        }
        var selected = plan.time();
        if (selected == null
                && businessDay != null
                && plan.filters().stream().noneMatch(filter -> businessDay.equals(filter.field()))) {
            // 允许早期计划用等值日期条件，已经声明的业务日不再补一次。
            selected = new TimeSelection(businessDay, "DAY", null, null, null);
        }
        if (selected == null) {
            return plan;
        }
        return withTime(plan, plan.filters(), resolvePeriod(basis, plan, selected, today));
    }

    /**
     * 指标定义明确需要单个业务日期，且同版依赖中恰有一个可信 DATE 字段时才补默认日期。
     */
    private Field businessDate(Basis basis, Plan plan) {
        if (!ARITY.containsKey(plan.operation())) {
            // 明细和普通实时字段保持其原有查询范围。
            return null;
        }
        var definition = metric(basis, plan);
        if (!definition.getContent().path("usage").asText().contains("业务日期")) {
            // 例如统计存在快照的 SKU，已发布定义允许跨日去重。
            return null;
        }
        var dates = definition.getDependencies().stream()
                .filter(fact -> "COLUMN".equals(fact.getKind()))
                .map(fact -> basis.fields().get(fact.getTargetId()))
                .filter(field -> field != null && dateField(basis, field))
                .distinct()
                .toList();
        return dates.size() == 1 ? dates.get(0) : null;
    }

    /**
     * 明示沿用日期或其他原条件时使用历史业务日，明确给出新日期则交给正常规划。
     */
    boolean keepsBusinessDay(String question) {
        return !REQUESTED_DATE.matcher(question).find()
                && !CURRENT_DAY.matcher(question).find()
                && (REFERENCED_DATE.matcher(question).find()
                        || UNCHANGED_CONDITIONS.matcher(question).find());
    }

    /**
     * 复用本人当前主题最近一次确认的单个业务日期。期间不明确时不猜测基准日。
     */
    Plan followBusinessDay(Basis basis, Plan plan, Plan previous, int offset) {
        if (plan.filters() == null
                || previous == null
                || !Objects.equals(plan.binding(), previous.binding())
                || !Objects.equals(plan.metric(), previous.metric())
                || offset == 0
                        && plan.time() != null
                        && "TREND".equals(plan.time().mode())) {
            // 不向不同口径或不同知识绑定借用日期。
            return null;
        }
        var time = previous.time();
        if (time == null) {
            // 兼容已交付的早期计划在普通等值条件里保存业务日期。
            var dateFilters = (previous.filters() == null ? List.<QueryProtocol.Condition>of() : previous.filters())
                    .stream().filter(filter -> dateField(basis, filter.field())).toList();
            if (dateFilters.size() != 1) {
                return null;
            }
            var date = dateFilters.get(0);
            time = new TimeSelection(date.field(), "DAY", date.value(), date.value(), false);
        }
        if (!"DAY".equals(time.mode())
                || time.start() == null
                || !time.start().equals(time.end())
                || !dateField(basis, time.field())) {
            // 多日结果没有唯一的前一天。
            return null;
        }
        var field = time.field();
        var date = parseDay(time.start()).plusDays(offset).toString();
        var filters = plan.filters().stream()
                .filter(filter -> !field.equals(filter.field()))
                .toList();
        return withTime(plan, filters, new TimeSelection(field, "DAY", date, date, false));
    }

    /** 只移动已验证日指标的时间范围，按日保留原计算和筛选，避免快照跨日相加。 */
    Plan followPreviousWeek(Basis basis, Plan previous, LocalDate anchor) {
        var time = previous.time();
        if (time == null || time.field() == null || previous.filters() == null || previous.operation() == null
                || !ARITY.containsKey(previous.operation())
                || !dateField(basis, time.field())) {
            // 尚未验证逐日统计口径时不能按历史结果推导新范围。
            return null;
        }
        var date = time.field();
        var filters = previous.filters().stream().filter(filter -> !date.equals(filter.field())).toList();
        var monday = QueryDates.previousMonday(anchor);
        return withTime(previous, filters,
                new TimeSelection(date, "TREND", monday.toString(), monday.plusDays(6).toString(), false));
    }

    /**
     * 只改变业务日期条件，保留同一结构化计划中已确定的指标、字段和其他业务参数。
     */
    private Plan withTime(Plan plan, List<QueryProtocol.Condition> filters, TimeSelection time) {
        return new Plan(
                plan.decision(),
                plan.clarification(),
                plan.binding(),
                plan.operation(),
                plan.metric(),
                plan.unit(),
                plan.fields(),
                filters,
                time);
    }

    /**
     * 仅允许来自用户、已确认上文或发布材料的商品编码。模型翻译名称后仍需独立语义核对。
     */
    QueryUsage.ParameterUse checkProductCodes(Basis basis, Plan plan, String question, QueryProtocol.Memory memory) {
        boolean hasProduct = false;
        boolean published = false;
        var context = new LinkedHashSet<String>();
        // 自然周追问和日期路径共用完整句式，其他商品指代沿用原判断。
        boolean followsProduct = PRODUCT_CONTINUATION.matcher(question).find()
                || QueryDates.previousWeekFollowup(question);
        for (var filter : plan.filters()) {
            // 只处理被确认为商品编码的列，其他等值业务条件沿用原有验证。
            String label = basis.labels().get(filter.field());
            boolean code = isProductCode(label);
            boolean id = label != null && PRODUCT_ID_LABEL.matcher(label).find();
            if (!code && !id) {
                continue;
            }
            hasProduct = true;
            Pattern exact = Pattern.compile("(?i)(?<![\\w-])" + Pattern.quote(filter.value()) + "(?![\\w-])");
            if (exact.matcher(question).find()
                    && (code || EXPLICIT_IDENTIFIER.matcher(question).find())) {
                // 编码必须完整匹配，不能由短前缀匹配另一个商品。
                continue;
            }
            var inherited = Stream.concat(memory.recent().stream(), memory.earlier().stream())
                    .filter(turn -> turn.plan() != null
                            && Objects.equals(turn.plan().binding(), plan.binding())
                            && turn.plan().filters() != null
                            && turn.plan().filters().contains(filter))
                    .findFirst();
            if (inherited.isPresent() && followsProduct) {
                // 记录实际为这个参数提供依据的原轮次。
                context.add(inherited.orElseThrow().requestId());
                continue;
            }
            if (id || !exact.matcher(basis.text()).find()) {
                // 未发布的商品码和模型猜出的数值主键不能作为数据库参数。
                throw QueryFailure.knowledge();
            }
            if (QueryProductNames.declares(basis, filter.value())) {
                var resolved = QueryProductNames.resolve(List.of(basis), question);
                if (resolved == null || !resolved.confirmed() || !filter.value().equalsIgnoreCase(resolved.code())) {
                    // 有明确发布的名称和规格时，不能换成相似商品或其他口味。
                    throw QueryFailure.knowledge();
                }
            }
            published = true;
        }
        if (!hasProduct
                && ARITY.containsKey(plan.operation())
                && metric(basis, plan).getContent().path("usage").asText().matches("(?is).*先固定[^；。]{0,24}SKU.*")) {
            // 指标要求锁定单个商品时，不能把未识别商品变成全量库存查询。
            throw QueryFailure.knowledge();
        }
        return new QueryUsage.ParameterUse(List.copyOf(context), published);
    }

    /**
     * 只认同版治理明确声明的商品编码列。
     */
    static boolean isProductCode(String label) {
        return label != null && PRODUCT_CODE_LABEL.matcher(label).find();
    }

    /**
     * 普通全仓库存问法已确定名称、规格和日历后，从同版发布公式生成有限计划。
     */
    Plan namedStock(QueryProductNames.Match product, LocalDate day) {
        var basis = product.basis();
        var facts = basis.facts().values().stream()
                .filter(fact -> "METRIC".equals(fact.getTarget().getKind())
                        && fact.getLabel().contains("可售库存"))
                .toList();
        if (facts.size() != 1) {
            // 仍有多个口径时交给正常语义规划。
            return null;
        }
        var fact = facts.get(0);
        var formula = PUBLISHED_STOCK_FORMULA.matcher(
                fact.getContent().path("rule").asText().replaceAll("\\s+", ""));
        if (!formula.matches()) {
            return null;
        }
        var fields =
                List.of(new Field(formula.group(1), formula.group(2)), new Field(formula.group(3), formula.group(4)));
        if (!fields.stream().allMatch(basis.labels()::containsKey)) {
            // 不能通过公式中未获准的列取数。
            return null;
        }
        var filters = List.of(new QueryProtocol.Condition(product.field(), product.code()));
        String binding = Ids.text(basis.binding().id());
        var plan = new Plan("READY", null, binding, "SUM_DIFFERENCE", fact.getLabel(), null, fields, filters);
        var date = businessDate(basis, plan);
        if (date == null) {
            return null;
        }
        return day == null
                ? plan
                : withTime(plan, filters, new TimeSelection(date, "DAY", day.toString(), day.toString(), false));
    }

    /** 明确日期优先，未给日期时只使用原请求时间锚点。 */
    private TimeSelection resolvePeriod(Basis basis, Plan plan, TimeSelection selected, LocalDate today) {
        if (selected.field() == null
                || !dateField(basis, selected.field())
                || !("DAY".equals(selected.mode()) || "TREND".equals(selected.mode()))) {
            throw QueryFailure.knowledge();
        }
        boolean trend = "TREND".equals(selected.mode());
        if (trend && !ARITY.containsKey(plan.operation())) {
            throw QueryFailure.knowledge();
        }
        LocalDate start = selected.start() == null ? null : parseDay(selected.start());
        LocalDate end = selected.end() == null ? null : parseDay(selected.end());
        boolean defaulted = start == null && end == null;
        var exact = plan.filters().stream()
                .filter(filter -> selected.field().equals(filter.field()))
                .toList();
        if (!exact.isEmpty()) {
            // 同一日期上的等值与区间只能表达同一个已明确业务日。
            var day = parseDay(exact.get(0).value());
            if (exact.size() != 1 || start != null && !start.equals(day) || end != null && !end.equals(day)) {
                throw QueryFailure.knowledge();
            }
            start = end = day;
            defaulted = false;
        }
        if (trend) {
            end = end == null ? today : end;
            start = start == null ? end.minusDays(6) : start;
        } else {
            start = start != null ? start : end != null ? end : today;
            end = end == null ? start : end;
            if (!start.equals(end)) {
                throw QueryFailure.knowledge();
            }
        }
        if (end.isBefore(start) || java.time.temporal.ChronoUnit.DAYS.between(start, end) > 365) {
            throw QueryFailure.resource();
        }
        return new TimeSelection(selected.field(), selected.mode(), start.toString(), end.toString(), defaulted);
    }

    /** 日期维度必须来自同版治理引用与原生 DATE 字段。 */
    private boolean dateField(Basis basis, Field field) {
        var table = basis.tables().get(field.table());
        return table != null
                && basis.labels().containsKey(field)
                && table.entry().named(field.column()) != null
                && "date".equalsIgnoreCase(table.entry().named(field.column()).nativeType());
    }

    /** 日期解析失败不能变成数据库的隐式日期转换。 */
    private LocalDate parseDay(String text) {
        try {
            return LocalDate.parse(text);
        } catch (java.time.DateTimeException invalid) {
            throw QueryFailure.invalidParameter();
        }
    }

    /** 编译有限读操作，并按已确认指标及所有实际依赖检查口径。 */
    Compiled compile(Basis basis, Plan plan, boolean mysql, String database) {
        if (plan.operation() == null
                || plan.fields() == null
                || plan.filters() == null
                || plan.fields().isEmpty()
                || plan.fields().size() > 4
                || plan.filters().size() > 8) {
            throw QueryFailure.knowledge();
        }
        var arity = ARITY.get(plan.operation());
        if (arity == null && !"VALUES".equals(plan.operation())
                || arity != null && arity != plan.fields().size()) {
            throw QueryFailure.knowledge();
        }
        var required = new LinkedHashSet<String>();
        var usage = new QueryUsage.Capture(basis);
        for (var field : plan.fields()) {
            // 接受的投影与库表标识全部来自真实目录。
            checkField(basis, field);
            required.add(field.table());
            usage.field(field, arity == null ? "SELECT" : "COMPUTE");
        }
        for (var filter : plan.filters()) {
            // 列必须在同版证据中，值不参与 SQL 拼接。
            if (filter == null
                    || filter.value() == null
                    || filter.value().isBlank()
                    || filter.value().length() > 120) {
                throw QueryFailure.knowledge();
            }
            checkField(basis, filter.field());
        }
        // AND 条件顺序不改变业务含义，固定顺序也使等价执行指纹一致。
        var filters = plan.filters().stream()
                .filter(item -> plan.time() == null || !plan.time().field().equals(item.field()))
                .sorted(Comparator.comparing(
                                (QueryProtocol.Condition item) -> item.field().table())
                        .thenComparing(item -> item.field().column())
                        .thenComparing(QueryProtocol.Condition::value))
                .toList();
        filters.forEach(filter -> required.add(filter.field().table()));
        var columns = new ArrayList<QueryColumnVO>();
        var projection = new ArrayList<>(plan.fields());
        var unit = "";
        var meaning = "";
        Field unitField = null;
        if (arity != null) {
            // 核验真实表达式与确认指标，不让模型擅改字段、单位或必需连接。
            var metric = metric(basis, plan);
            usage.fact(metric);
            for (var fact : metric.getDependencies()) {
                if ("COLUMN".equals(fact.getKind())) {
                    // 库存覆盖计数即使只投影商品键也必须关联快照存在性。
                    var field = basis.fields().get(fact.getTargetId());
                    if (field == null) {
                        throw QueryFailure.knowledge();
                    }
                    required.add(field.table());
                }
            }
            var metricUsage = metric.getContent().path("usage").asText();
            if (!"COUNT_DISTINCT".equals(plan.operation()) && metricUsage.contains("单位")) {
                // 定义要求单位一致时从实际同行回读确认的计量字段。
                var candidates = metric.getDependencies().stream()
                        .filter(fact -> "COLUMN".equals(fact.getKind()))
                        .map(fact -> basis.fields().get(fact.getTargetId()))
                        .filter(field -> measuresUnit(basis, field))
                        .distinct()
                        .toList();
                if (candidates.size() == 1) {
                    unitField = candidates.get(0);
                    checkField(basis, unitField);
                    projection.add(unitField);
                    usage.field(unitField, "UNIT_CHECK");
                } else if (SAME_UNIT.matcher(metricUsage).find()) {
                    // 无法读取可信计量单位时，不执行可能混加的业务查询。
                    throw QueryFailure.knowledge();
                }
            }
            var matcher = java.util.regex.Pattern.compile("单位为([\\p{L}]{1,5})").matcher(metricUsage);
            unit = matcher.find() ? matcher.group(1) : "";
            if (unit.isEmpty()
                    && plan.unit() != null
                    && plan.unit().matches("[\\p{L}]{1,5}")
                    && java.util.regex.Pattern.compile("\\d\\s*" + java.util.regex.Pattern.quote(plan.unit()))
                            .matcher(basis.text())
                            .find()) {
                // 只有命中证据实际出现的单位才可以与本次结果一起交付。
                unit = plan.unit();
            }
            meaning = metric.getContent().path("meaning").asText() + metricUsage;
            columns.add(new QueryColumnVO("value", metric.getLabel()));
        } else {
            // 明细由确切的已确认字段集合决定，禁止星号和模型别名。
            plan.fields().forEach(f -> columns.add(new QueryColumnVO(f.column(), f.column())));
        }

        var time = plan.time();
        boolean trend = time != null && "TREND".equals(time.mode());
        if (time != null) {
            if (!dateField(basis, time.field())) {
                throw QueryFailure.knowledge();
            }
            required.add(time.field().table());
            projection.add(time.field());
            usage.field(time.field(), "FILTER");
            if (trend) {
                // 趋势的日期也是结果维度、分组键和排序键。
                usage.field(time.field(), "SELECT");
                usage.field(time.field(), "GROUP");
                usage.field(time.field(), "ORDER");
            }
        }
        var aliases = new LinkedHashMap<String, String>();
        required.forEach(name -> aliases.put(name, "t" + aliases.size()));
        var selected =
                plan.fields().stream().map(f -> sqlField(f, aliases, mysql)).toList();
        String select = arity == null ? String.join(", ", selected) : aggregate(plan.operation(), selected);
        String date = time == null ? null : sqlField(time.field(), aliases, mysql);
        if (trend) {
            columns.add(0, new QueryColumnVO("date", "业务日期"));
            select = date + ", " + select;
        }
        if (unitField != null) {
            var identifier = sqlField(unitField, aliases, mysql);
            select += ", MIN(" + identifier + "), MAX(" + identifier + "), COUNT(" + identifier + ") = COUNT(*)";
        }
        var sql = new StringBuilder("SELECT ").append(select).append(" FROM ");
        var first = required.iterator().next();
        sql.append(sqlTable(basis, first, mysql, database)).append(' ').append(aliases.get(first));
        join(basis, required, aliases, first, mysql, database, sql, usage);

        var predicates = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        var scope = new ArrayList<String>();
        if (time != null) {
            predicates.add(date + (trend ? " BETWEEN ? AND ?" : " = ?"));
            parameters.add(parseDay(time.start()));
            if (trend) {
                parameters.add(parseDay(time.end()));
            }
            var window = trend ? time.start() + " 至 " + time.end() : time.start();
            scope.add(Boolean.TRUE.equals(time.defaulted()) ? (trend ? "默认最近 7 天 " : "默认当前业务日 ") + window : window);
        }
        for (var filter : filters) {
            // 采用真实列类型，避免依赖数据库隐式类型转换。
            var field = filter.field();
            var column = basis.tables().get(field.table()).entry().named(field.column());
            usage.field(field, "FILTER");
            predicates.add(sqlField(field, aliases, mysql) + " = ?");
            var argument = parameter(column.nativeType(), filter.value());
            parameters.add(argument);
            // 业务取值可能本身受限，只有执行逻辑使用形如 sku.code = ? 的实际值。
            if (argument instanceof LocalDate day) {
                scope.add(day.toString());
            }
        }
        if (!predicates.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", predicates));
        }
        if (trend) {
            sql.append(" GROUP BY ").append(date).append(" ORDER BY ").append(date);
        }
        var scopeText = scope.isEmpty() ? meaning : String.join("、", scope) + "。 " + meaning;
        return new Compiled(
                sql.toString(),
                parameters,
                columns,
                List.copyOf(projection),
                unit,
                scopeText,
                unitField != null,
                trend ? new QuerySeriesWindowVO(time.start(), time.end()) : null,
                usage.finish(required));
    }

    /** 聚合必须匹配发布中已确认的指标公式。 */
    private GovernanceFactVO metric(Basis basis, Plan plan) {
        Integer arity = ARITY.get(plan.operation());
        if (arity == null || plan.fields() == null || plan.fields().size() != arity) {
            throw QueryFailure.knowledge();
        }
        var logical = aggregate(
                plan.operation(),
                plan.fields().stream().map(f -> f.table() + "." + f.column()).toList());
        var matches = basis.facts().values().stream()
                .filter(f -> "METRIC".equals(f.getTarget().getKind())
                        && normal(logical)
                                .equals(normal(f.getContent().path("rule").asText())))
                .toList();
        return matches.stream()
                .filter(f -> f.getLabel().equals(plan.metric()))
                .findFirst()
                .orElseGet(() -> {
                    if (matches.size() == 1) {
                        return matches.get(0);
                    }
                    throw QueryFailure.knowledge();
                });
    }

    /**
     * 通过发布字段语义识别行业自己的单位列名，并兼容现有目录命名。
     */
    private boolean measuresUnit(Basis basis, Field field) {
        return field.column().equals("unit")
                || field.column().endsWith("_unit")
                || basis.labels().getOrDefault(field, "").contains("计量单位");
    }

    /** 用发布内唯一的已确认连接构造查询树，拒绝猜测任意连接。 */
    private void join(
            Basis basis,
            Set<String> required,
            Map<String, String> aliases,
            String first,
            boolean mysql,
            String database,
            StringBuilder sql,
            QueryUsage.Capture usage) {
        var remaining = new LinkedHashSet<>(required);
        remaining.remove(first);
        var linked = new LinkedHashSet<>(Set.of(first));
        var relations = relations(basis);
        while (!remaining.isEmpty()) {
            var ready = relations.stream()
                    .filter(r -> linked.contains(r.left()) && remaining.contains(r.right())
                            || linked.contains(r.right()) && remaining.contains(r.left()))
                    .toList();
            if (ready.isEmpty()) {
                throw QueryFailure.knowledge();
            }
            var relation = ready.get(0);
            var next = linked.contains(relation.left()) ? relation.right() : relation.left();
            if (ready.stream()
                            .filter(r -> r.left().equals(next) || r.right().equals(next))
                            .count()
                    != 1) {
                // 同一张表存在多个可解释连接时需要更明确的关联选择。
                throw QueryFailure.knowledge();
            }
            var conditions = relation.keys().stream()
                    .map(p -> sqlField(p.left(), aliases, mysql) + " = " + sqlField(p.right(), aliases, mysql))
                    .collect(Collectors.joining(" AND "));
            usage.fact(relation.fact());
            for (var key : relation.keys()) {
                // 每个实际写入 ON 的复合键字段都保留稳定编号。
                usage.field(key.left(), "JOIN");
                usage.field(key.right(), "JOIN");
            }
            sql.append(" JOIN ")
                    .append(sqlTable(basis, next, mysql, database))
                    .append(' ')
                    .append(aliases.get(next))
                    .append(" ON ")
                    .append(conditions);
            remaining.remove(next);
            linked.add(next);
        }
    }

    /** 从同版确认读取复合键配对，以字段稳定身份定位两端。 */
    private List<Relation> relations(Basis basis) {
        var relations = new ArrayList<Relation>();
        for (var fact : basis.facts().values()) {
            if (!"RELATION".equals(fact.getTarget().getKind())) {
                continue;
            }
            var pairs = new ArrayList<Pair>();
            for (JsonNode pair : fact.getContent().path("pairs")) {
                var left = basis.fields().get(pair.path("left").asText());
                var right = basis.fields().get(pair.path("right").asText());
                if (left == null || right == null || left.table().equals(right.table())) {
                    // 不完整的关联不能作为隐式连接使用。
                    pairs.clear();
                    break;
                }
                pairs.add(new Pair(left, right));
            }
            if (!pairs.isEmpty()) {
                var key = pairs.get(0);
                if (pairs.stream()
                        .allMatch(p -> p.left().table().equals(key.left().table())
                                && p.right().table().equals(key.right().table()))) {
                    relations.add(new Relation(key.left().table(), key.right().table(), pairs, fact));
                }
            }
        }
        return relations;
    }

    /** 严格检查模型字段是当前片段中的实际字段，表由 JDBC 真实目录提供。 */
    private void checkField(Basis basis, Field field) {
        if (field == null
                || !basis.labels().containsKey(field)
                || !basis.tables().containsKey(field.table())) {
            throw QueryFailure.knowledge();
        }
    }

    /** 运算结构只来自服务端允许的语法。 */
    private String aggregate(String operation, List<String> fields) {
        return switch (operation) {
            case "COUNT_DISTINCT" -> "COUNT(DISTINCT " + fields.get(0) + ")";
            case "SUM" -> "SUM(" + fields.get(0) + ")";
            case "SUM_DIFFERENCE" -> "SUM(" + fields.get(0) + " - " + fields.get(1) + ")";
            default -> throw QueryFailure.knowledge();
        };
    }

    /** 指标文本只用来比对，不直接执行。 */
    private String normal(String text) {
        return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 只拼接数据库返回的真实库／模式和表标识。 */
    private String sqlTable(Basis basis, String name, boolean mysql, String database) {
        var object = basis.tables().get(name).entry().observation().structure().object();
        if (!"TABLE".equals(object.type())) {
            // 首条链路只编译可直接证明血缘的普通表。
            throw new QueryFailure("OUT_OF_SCOPE");
        }
        return quote(mysql ? database : object.schema(), mysql) + "." + quote(object.name(), mysql);
    }

    /** 模型提议已与真实目录逐项相等，别名由服务端生成。 */
    private String sqlField(Field field, Map<String, String> aliases, boolean mysql) {
        return aliases.get(field.table()) + "." + quote(field.column(), mysql);
    }

    /** 适配当前源的标识引用字符。 */
    private String quote(String name, boolean mysql) {
        String mark = mysql ? "\u0060" : "\"";
        return mark + name.replace(mark, mark + mark) + mark;
    }

    /** 将受控条件映射到明确的日期、数字或文本参数。 */
    private Object parameter(String type, String value) {
        String nativeType = type.toLowerCase(Locale.ROOT);
        try {
            if (nativeType.equals("date")) {
                return LocalDate.parse(value);
            }
            if (nativeType.matches(
                    "(tinyint|smallint|mediumint|int|integer|bigint|decimal|numeric|double|float|real).*")) {
                var number = new BigDecimal(value).stripTrailingZeros();
                if (number.precision() > 65 || Math.abs((long) number.scale()) > 65) {
                    // 控制 JDBC 参数展开的上限，拒绝不适用于有限查询的数量级。
                    throw QueryFailure.invalidParameter();
                }
                return number;
            }
            if (nativeType.matches("(char|varchar|character|text|enum).*")) {
                return value;
            }
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            // 参数不能被数据库自动截断或解释成另一种业务条件。
            throw QueryFailure.invalidParameter();
        }
        throw QueryFailure.knowledge();
    }
}
