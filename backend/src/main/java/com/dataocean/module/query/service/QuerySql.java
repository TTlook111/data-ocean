package com.dataocean.module.query.service;

import com.dataocean.common.Ids;
import com.dataocean.module.governance.entity.dto.MetricRulesDTO;
import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
import com.dataocean.module.governance.service.MetricFormula;
import com.dataocean.module.governance.service.MetricRules;
import com.dataocean.module.query.entity.vo.QueryColumnVO;
import com.dataocean.module.query.entity.vo.QuerySeriesWindowVO;
import com.dataocean.module.query.service.QueryCorpus.Basis;
import com.dataocean.module.query.service.QueryJoinPath.Relation;
import com.dataocean.module.query.service.QueryProtocol.Condition;
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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 把经治理的有限关系和运算编译为参数化只读 SQL，任何模型文本都不成为 SQL。
 */
final class QuerySql {

    /**
     * 输出列、实际血缘和参数固定后才允许派发。
     *
     * @param joinKeys 实际等值连接键对，忽略键对顺序和等式方向
     */
    record Compiled(
            String sql,
            List<Object> parameters,
            List<QueryColumnVO> columns,
            List<Field> projection,
            String unit,
            String scope,
            boolean verifiesUnit,
            QuerySeriesWindowVO series,
            QueryUsage usage,
            Set<Set<Field>> joinKeys) {}

    /**
     * 实际筛选列及其 JDBC 类型化值，用于核对固定条件和跨发布等价性。
     */
    record BoundFilter(Field field, Object value, boolean excluded) {}

    /**
     * 按实际源列语义核对请求范围，字符串比较由源库提供。
     */
    @FunctionalInterface
    interface ValueComparison {
        boolean same(Basis basis, Field field, Object first, Object second);
    }

    /**
     * 用户绑定、合并及候选比较共同核对实际列、方向和数据库等值。
     */
    static boolean sameBoundFilter(Basis basis, BoundFilter first, BoundFilter second, ValueComparison comparison) {
        return first.field().equals(second.field())
                && first.excluded() == second.excluded()
                && comparison.same(basis, first.field(), first.value(), second.value());
    }

    /** 标准聚合字段数，其他运算一律不接受。 */
    private static final Map<String, Integer> ARITY = Map.of("COUNT_DISTINCT", 1, "SUM", 1, "SUM_DIFFERENCE", 2);

    /**
     * 指标修订把可执行规则存为固定 JSON，编译时按明确类型读取。
     */
    private static final JsonMapper RULES_JSON = JsonMapper.builder().build();

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
        return normalize(plan, false);
    }

    /**
     * 已有原话条件时在绑定后再核对剩余模型过滤，便于去掉对用户条件的重复表达。
     */
    static Plan normalize(Plan plan, boolean deferFilterValidation) {
        if (!deferFilterValidation
                && plan.filters() != null
                && plan.filters().stream().anyMatch(filter -> filter != null && filter.excluded())) {
            // 规划模型不产生排除谓词，用户条件由 Java 另行绑定。
            throw QueryFailure.knowledge();
        }
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
        return resolveTime(basis, plan, today, question, hasHistory, QueryDates.requestedDay(question, today));
    }

    /**
     * 采用从原问题与补充解析的独立日期，再核对候选时间条件。
     */
    Plan resolveTime(
            Basis basis, Plan plan, LocalDate today, String question, boolean hasHistory, LocalDate requestedDay) {
        if (plan.operation() == null
                || plan.filters() == null
                || plan.filters().stream()
                        .anyMatch(filter -> filter == null || filter.field() == null || filter.value() == null)) {
            throw QueryFailure.knowledge();
        }
        var businessDay = businessDate(basis, plan);
        plan = dateInputs(basis, plan, question, hasHistory, requestedDay);
        var time = plan.time();
        if (requestedDay != null) {
            // 绑定用户原话中唯一的日历日期，模型只提供候选结构。
            var date = time == null ? businessDay : time.field();
            if (date == null) {
                // 明细计划可用唯一的已确认日期筛选列承接用户所给日期。
                var fields = plan.filters().stream()
                        .map(Condition::field)
                        .filter(field -> QueryCorpus.isDateField(basis, field))
                        .distinct()
                        .toList();
                date = fields.size() == 1 ? fields.get(0) : null;
            }
            if (date != null && QueryCorpus.isDateField(basis, date)) {
                // 同列的模型日期条件全部替换成已确认日，保留其他筛选。
                var field = date;
                var filters = plan.filters().stream()
                        .filter(filter -> !field.equals(filter.field()))
                        .toList();
                String day = requestedDay.toString();
                var selection = new TimeSelection(field, timeMode(time), day, day, false);
                var resolved = withTime(plan, filters, selection);
                return withTime(resolved, filters, resolvePeriod(basis, resolved, selection, today));
            }
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
     * 仅保留实际提出的日期输入。候选各自的业务日默认值在此之后计算。
     */
    Plan dateInputs(Basis basis, Plan plan, String question, boolean hasHistory, LocalDate requestedDay) {
        if (requestedDay != null) {
            // 原话明确点出日期列时，以该列替换模型猜测的列。
            var namedDates = basis.labels().keySet().stream()
                    .filter(field -> QueryCorpus.isDateField(basis, field) && mentionsField(basis, question, field))
                    .toList();
            if (namedDates.size() == 1) {
                return withTime(
                        plan,
                        plan.filters(),
                        new TimeSelection(
                                namedDates.get(0),
                                timeMode(plan.time()),
                                requestedDay.toString(),
                                requestedDay.toString(),
                                false));
            }
        } else if (plan.filters() != null
                && !REQUESTED_DATE.matcher(question).find()
                && !(hasHistory && REFERENCED_DATE.matcher(question).find())) {
            // 没有实际日期要求时，清除猜测的筛选和单日列，趋势仍保留其分组列。
            var filters = plan.filters().stream()
                    .filter(filter -> filter == null || !QueryCorpus.isDateField(basis, filter.field()))
                    .toList();
            var time = plan.time();
            var trend = time != null && "TREND".equals(time.mode())
                    ? new TimeSelection(time.field(), "TREND", null, null, null)
                    : null;
            return withTime(plan, filters, trend);
        }
        return plan;
    }

    /**
     * 业务日期只取已确认执行规则中的稳定 DATE 列，缺少完整证据的指标不跨日执行。
     */
    private Field businessDate(Basis basis, Plan plan) {
        if (!ARITY.containsKey(plan.operation())) {
            // 明细和普通实时字段保持其原有查询范围。
            return null;
        }
        var rules = metricRules(basis, metric(basis, plan), plan.metric());
        return "BUSINESS_DAY".equals(rules.getDateMode()) ? basis.fields().get(rules.getDateFieldId()) : null;
    }

    /**
     * 将确认后的固定条件合并进实际计划。已提及同列时校验类型化值，拒绝替换指标范围。
     */
    Plan applyMetricFilters(Basis basis, Plan plan) {
        if (plan.operation() == null || !ARITY.containsKey(plan.operation()) || plan.filters() == null) {
            // 明细及无效计划仍走原有检查。
            return plan;
        }
        var fixed = fixedFilters(basis, metricRules(basis, metric(basis, plan), plan.metric()));
        if (fixed.isEmpty()) {
            return plan;
        }
        var filters = new ArrayList<>(plan.filters());
        for (var required : fixed) {
            // 编译和复核统一使用已确认的源字段原值。
            var field = required.field();
            var bound = bindFilter(basis, required);
            for (var filter : filters) {
                if (filter == null || filter.field() == null || filter.value() == null) {
                    throw QueryFailure.knowledge();
                }
                if (field.equals(filter.field())
                        && bound.value().equals(bindFilter(basis, filter).value()) == filter.excluded()) {
                    // 用户或模型给出的相反条件不能将已确认指标改成另一个口径。
                    throw QueryFailure.knowledge();
                }
            }
            filters.removeIf(filter -> field.equals(filter.field()) && !filter.excluded());
            filters.add(required);
        }
        return withTime(plan, filters, plan.time());
    }

    /**
     * 其他发布自行应用其固定条件。只保留用户可核实的条件和其它实际输入。
     */
    Plan independentBindingFilters(Basis basis, Plan plan, String question) {
        if (plan.filters() == null || plan.filters().isEmpty()) {
            // 原计划没有筛选时可直接比较。
            return plan;
        }
        var fixed = provenFixedFilters(basis, plan);
        if (fixed.isEmpty()) {
            // 只删除能对账到当前发布的固定值。
            return plan;
        }
        try {
            var values = fixed.stream().collect(Collectors.toMap(Condition::field, value -> bindFilter(basis, value)));
            var filters = plan.filters().stream()
                    .filter(filter -> filter == null
                            || !values.containsKey(filter.field())
                            || filter.value() == null
                            || !values.get(filter.field()).equals(bindFilter(basis, filter))
                            || requestedFilter(basis, filter, question)
                            || mentionsMappedValue(basis, filter, question))
                    .toList();
            return withTime(plan, filters, plan.time());
        } catch (QueryFailure invalid) {
            // 输入绑定失败时由原编译流程识别并走参数修复，保留原提议。
            return plan;
        }
    }

    /**
     * 只认当前列确认修订里能够唯一指向请求取值的业务词。
     */
    private boolean mentionsMappedValue(Basis basis, Condition filter, String question) {
        var column = QueryCorpus.confirmedColumn(basis, filter.field());
        var fact = column == null ? null : basis.fact("COLUMN", column.columnId());
        if (fact == null || !fact.getContent().path("valueMappings").isArray()) {
            // 没有审核过的值对照时，不能用指标说明猜测用户指定了哪个值。
            return false;
        }
        var mentioned = new LinkedHashSet<String>();
        for (var value : fact.getContent().path("valueMappings")) {
            if (mentionsText(question, value.path("meaning").asText())) {
                // 同时出现多个不同业务值时保留歧义。
                mentioned.add(value.path("code").asText());
            }
        }
        return mentioned.equals(Set.of(filter.value()));
    }

    /**
     * 溯源检查使用能完整证明的确认值。无可证口径时保留原有编译和商品核验。
     */
    List<Condition> provenFixedFilters(Basis basis, Plan plan) {
        if (plan.operation() == null || !ARITY.containsKey(plan.operation())) {
            // 明细等其他运算没有聚合指标的固定条件。
            return List.of();
        }
        try {
            return fixedFilters(basis, metricRules(basis, metric(basis, plan), plan.metric()));
        } catch (QueryFailure incomplete) {
            // 仅作为固定值来源的查验，缺证由各候选的编译规则处理。
            return List.of();
        }
    }

    /**
     * 将验证过的治理依赖解析为实际筛选列和值。
     */
    private List<Condition> fixedFilters(Basis basis, MetricRulesDTO rules) {
        return rules.getFixedFilters().stream()
                .map(filter -> new Condition(basis.fields().get(filter.getFieldId()), filter.getValue()))
                .toList();
    }

    /**
     * 比较实际生效的列和值，日期维度的绑定由时间条件提供。
     */
    static Set<BoundFilter> boundFilters(Basis basis, Plan plan) {
        return plan.filters().stream()
                .filter(filter -> plan.time() == null || !plan.time().field().equals(filter.field()))
                .map(filter -> bindFilter(basis, filter))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 固定条件匹配和跨发布比较使用编译时相同的目录列与参数类型。
     */
    static BoundFilter bindFilter(Basis basis, Condition filter) {
        var column = QueryCorpus.confirmedColumn(basis, filter.field());
        if (column == null) {
            // 不给还未获得确认的列推断可比较的参数类型。
            throw QueryFailure.knowledge();
        }
        return new BoundFilter(filter.field(), parameter(column.nativeType(), filter.value()), filter.excluded());
    }

    /**
     * 指标执行规则必须完整，且每个字段均属于已确认指标依赖和当前片段。
     */
    private MetricRulesDTO metricRules(Basis basis, GovernanceFactVO fact, String plannedMetric) {
        boolean identified = fact.getLabel().equals(plannedMetric);
        var content = fact.getContent().path("metricRules");
        if (content.isMissingNode() || content.isNull()) {
            // 只有已匹配实际公式的确认指标可定位到这次规则缺失。
            throw identified
                    ? QueryFailure.knowledge(new QueryFailure.Gap("METRIC_RULES", fact.getRevisionId(), List.of()))
                    : QueryFailure.knowledge();
        }
        var missing = !identified || fact.getDependencies() == null
                ? List.<String>of()
                : fact.getDependencies().stream()
                        .filter(dependency -> "COLUMN".equals(dependency.getKind()))
                        .map(dependency -> dependency.getTargetId())
                        .filter(id -> !basis.fields().containsKey(id))
                        .distinct()
                        .sorted()
                        .toList();
        if (!missing.isEmpty()) {
            // 确认的指标给出完整所需字段，同一指标的不同缺失集合分别办理。
            throw QueryFailure.knowledge(new QueryFailure.Gap("METRIC_COLUMNS", fact.getRevisionId(), missing));
        }
        var rules = confirmedMetricRules(basis, fact);
        if (rules == null) {
            // 旧发布或失效依赖无法作为当前执行依据。
            throw QueryFailure.knowledge();
        }
        return rules;
    }

    /**
     * 规划证据和 SQL 使用同一份可验证的确认规则。旧发布不输出推测的规则。
     */
    static MetricRulesDTO confirmedMetricRules(Basis basis, GovernanceFactVO fact) {
        var content = fact.getContent().path("metricRules");
        if (!content.isObject() || fact.getDependencies() == null) {
            // 旧发布缺少明确规则时不能用文字猜测默认日期或固定条件。
            return null;
        }
        try {
            var rules = RULES_JSON.treeToValue(content, MetricRulesDTO.class);
            var fields = fact.getDependencies().stream()
                    .filter(dependency -> "COLUMN".equals(dependency.getKind()))
                    .map(dependency -> dependency.getTargetId())
                    .collect(Collectors.toSet());
            return MetricRules.complete(rules, id -> {
                        // 即使目录存在该字段，指标未确认引用时也不得作为执行口径。
                        var column = fields.contains(id)
                                ? QueryCorpus.confirmedColumn(
                                        basis, basis.fields().get(id))
                                : null;
                        return column == null ? null : column.nativeType();
                    })
                    ? rules
                    : null;
        } catch (JacksonException invalid) {
            // 旧格式或非法治理正文不应成为模型参数修复任务。
            return null;
        }
    }

    /**
     * 明示沿用日期或其他原条件时使用历史业务日，明确给出新日期则交给正常规划。
     */
    boolean keepsBusinessDay(String question) {
        return !requestsDate(question) && (REFERENCED_DATE.matcher(question).find() || keepsOtherConditions(question));
    }

    /**
     * 即使本轮修改日期，也需在服务端核对要求保留的原执行范围。
     */
    boolean keepsOtherConditions(String question) {
        return UNCHANGED_CONDITIONS.matcher(question).find();
    }

    /**
     * 区分补充新日期与仅明确要移动的历史日期列。
     */
    boolean requestsDate(String question) {
        return REQUESTED_DATE.matcher(question).find()
                || CURRENT_DAY.matcher(question).find();
    }

    /**
     * 以实际历史条件为底稿，仅替换明确指定的日期和其他筛选。
     */
    Plan historicalConditions(Basis basis, Plan plan, Plan previous, String question, TimeSelection change) {
        if (plan.operation() == null
                || plan.filters() == null
                || !Objects.equals(plan.binding(), previous.binding())
                || !Objects.equals(plan.metric(), previous.metric())
                || plan.filters().stream()
                        .anyMatch(filter -> filter == null || filter.field() == null || filter.value() == null)) {
            // 历史条件只能并入同一已核验口径的有效计划。
            return null;
        }
        boolean keepOthers = change != null || keepsOtherConditions(question);
        var changes = plan.filters().stream()
                .filter(filter -> !QueryCorpus.isDateField(basis, filter.field()))
                .filter(filter -> !keepOthers || requestedFilter(basis, filter, question))
                .toList();
        var replaced = changes.stream().map(Condition::field).collect(Collectors.toSet());
        var priorTime = previous.time();
        var priorTimeField = priorTime == null ? null : priorTime.field();
        var changedDateField = change == null ? null : change.field();
        var filters = previous.filters().stream()
                .filter(filter -> !filter.field().equals(priorTimeField)
                        && !filter.field().equals(changedDateField))
                .filter(filter -> !replaced.contains(filter.field()))
                .filter(filter -> keepOthers || QueryCorpus.isDateField(basis, filter.field()))
                .collect(Collectors.toCollection(ArrayList::new));
        filters.addAll(changes);
        var time = change == null ? priorTime : change;
        if (priorTime != null && change != null && !priorTime.field().equals(change.field())) {
            // 单日可以作为另一日期维度的等值筛选同时保留。
            if ("DAY".equals(priorTime.mode())) {
                // 保留上一轮的单日，新日期承担本轮的时间维度。
                filters.add(new Condition(priorTime.field(), priorTime.start()));
            } else if ("DAY".equals(change.mode())) {
                // 保留原区间，本轮修改的另一日期以等值筛选表达。
                filters.add(new Condition(change.field(), change.start()));
                time = priorTime;
            } else {
                // 当前受控计划只支持一个日期区间。
                return null;
            }
        }
        if (time == null) {
            // 旧计划的单条日期筛选继续以正常的业务日表示。
            var dates = filters.stream()
                    .filter(filter -> QueryCorpus.isDateField(basis, filter.field()))
                    .toList();
            if (dates.size() == 1) {
                // 提取唯一业务日并去掉同列的重复筛选。
                var exact = dates.get(0);
                time = new TimeSelection(exact.field(), "DAY", exact.value(), exact.value(), false);
                filters.remove(exact);
            } else if (dates.isEmpty() && !keepOthers) {
                // 用户明确要求沿用日期时，缺少历史日期需要确认。
                return null;
            }
        }
        var base = change == null ? plan : previous;
        if (time != null && "TREND".equals(time.mode()) && !ARITY.containsKey(base.operation())) {
            // 逐日趋势口径只能继续用于支持聚合的运算。
            return null;
        }
        return withTime(base, filters, time);
    }

    /**
     * 日期追问沿用原发布、公式及输出字段，只从本轮计划读取待核实的筛选和时间。
     */
    Plan withHistoricalMetric(Plan proposal, Plan previous) {
        return withTime(previous, proposal.filters(), proposal.time());
    }

    /**
     * 只为唯一明确的日期列取得本轮的新时间条件。
     */
    TimeSelection changedHistoricalDate(
            Basis basis,
            Plan previous,
            Plan proposed,
            String question,
            Integer clarifiedOffset,
            LocalDate requestedDay) {
        var named = basis.labels().keySet().stream()
                .filter(field -> QueryCorpus.isDateField(basis, field) && mentionsField(basis, question, field))
                .toList();
        var historical = previous.filters().stream()
                .map(Condition::field)
                .filter(field -> QueryCorpus.isDateField(basis, field))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (previous.time() != null) {
            // 显式时间也属于已确认的日期候选。
            historical.add(previous.time().field());
        }
        var field = named.size() == 1
                ? named.get(0)
                : named.isEmpty() && historical.size() == 1
                        ? historical.iterator().next()
                        : null;
        if (field == null) {
            // 多日期列无法由模型建议直接消歧。
            return null;
        }
        if (requestedDay != null) {
            // 日期列由原口径和用户指代共同确定，日期值直接取自用户输入。
            String day = requestedDay.toString();
            return new TimeSelection(field, timeMode(proposed.time()), day, day, false);
        }
        if (clarifiedOffset != null) {
            // 用户只补充日期列时仍以历史实际值计算相邻日。
            var prior = previous.time();
            var exact = prior != null && prior.field().equals(field) ? prior : singleDateFilter(previous, field);
            if (exact == null || !"DAY".equals(exact.mode()) || !exact.start().equals(exact.end())) {
                // 只有单日可以作为相邻日基准。
                return null;
            }
            String day = parseDay(exact.start()).plusDays(clarifiedOffset).toString();
            return new TimeSelection(field, "DAY", day, day, false);
        }
        var time = proposed.time();
        return time != null && time.field().equals(field) && !Boolean.TRUE.equals(time.defaulted())
                ? time
                : singleDateFilter(proposed, field);
    }

    /**
     * 保留已提议的逐日聚合形式，普通单日查询使用等值条件。
     */
    private String timeMode(TimeSelection proposed) {
        return proposed != null && "TREND".equals(proposed.mode()) ? "TREND" : "DAY";
    }

    /**
     * 将已指定日期列上的单条等值条件转换为业务日。
     */
    private TimeSelection singleDateFilter(Plan plan, Field field) {
        var filters = plan.filters().stream()
                .filter(filter -> field.equals(filter.field()))
                .toList();
        if (filters.size() != 1) {
            // 多值或没有值都需要补充日期。
            return null;
        }
        String day = parseDay(filters.get(0).value()).toString();
        return new TimeSelection(field, "DAY", day, day, false);
    }

    /**
     * 原值的替换必须有用户指代，商品可由同版确认的映射提供编号。
     */
    private boolean requestedFilter(Basis basis, Condition filter, String question) {
        String value = filter.value();
        boolean namesField = mentionsField(basis, question, filter.field());
        if (isProductCode(basis.labels().get(filter.field()))) {
            // 商品别名和规格沿用发布正文中的已确认映射。
            var named = QueryProductNames.resolve(List.of(basis), question);
            return mentionsText(question, value)
                    || named != null && named.confirmed() && value.equalsIgnoreCase(named.code());
        }
        return mentionsText(question, value)
                && (namesField
                        || value.length() > 1 && !requestsDate(value) && !value.matches("[+-]?\\d+(?:\\.\\d+)?"));
    }

    /**
     * 可用目录名、发布标题和含义定位用户明确指出的筛选列。
     */
    private boolean mentionsField(Basis basis, String question, Field field) {
        var column = QueryCorpus.confirmedColumn(basis, field);
        var fact = column == null ? null : basis.fact("COLUMN", column.columnId());
        return mentionsText(question, field.column())
                || mentionsText(question, basis.labels().get(field))
                || fact != null && mentionsText(question, fact.getLabel());
    }

    /**
     * 对英文标识和代码按完整词记账，允许它们直接贴中文叙述。
     */
    private boolean mentionsText(String question, String value) {
        return value != null
                && !value.isBlank()
                && Pattern.compile("(?i)(?<![A-Za-z0-9_$-])" + Pattern.quote(value) + "(?![A-Za-z0-9_$-])")
                        .matcher(question)
                        .find();
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
            var historical = previous.filters();
            if (historical == null
                    || historical.stream()
                            .anyMatch(filter ->
                                    filter == null || QueryCorpus.confirmedColumn(basis, filter.field()) == null)) {
                // 片段看不到全部历史筛选时，不能据此断定哪一列是唯一日期。
                return null;
            }
            // 兼容以普通等值条件保存的历史计划，只有确定为单个日期时才可沿用。
            var dateFilters = historical.stream()
                    .filter(filter -> QueryCorpus.isDateField(basis, filter.field()))
                    .toList();
            if (dateFilters.size() != 1) {
                return null;
            }
            var date = dateFilters.get(0);
            time = new TimeSelection(date.field(), "DAY", date.value(), date.value(), false);
        }
        if (!"DAY".equals(time.mode())
                || time.start() == null
                || !time.start().equals(time.end())
                || !QueryCorpus.isDateField(basis, time.field())) {
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
        if (time == null
                || time.field() == null
                || previous.filters() == null
                || previous.operation() == null
                || !ARITY.containsKey(previous.operation())
                || !QueryCorpus.isDateField(basis, time.field())) {
            // 尚未验证逐日统计口径时不能按历史结果推导新范围。
            return null;
        }
        var date = time.field();
        var filters = previous.filters().stream()
                .filter(filter -> !date.equals(filter.field()))
                .toList();
        var monday = QueryDates.previousMonday(anchor);
        return withTime(
                previous,
                filters,
                new TimeSelection(
                        date, "TREND", monday.toString(), monday.plusDays(6).toString(), false));
    }

    /**
     * 只改变业务日期条件，保留同一结构化计划中已确定的指标、字段和其他业务参数。
     */
    Plan withTime(Plan plan, List<QueryProtocol.Condition> filters, TimeSelection time) {
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
        return checkProductCodes(basis, plan, question, memory, List.of());
    }

    /**
     * 本轮已从原话核实的商品条件直接采用用户依据，其余仍按原规则验证。
     */
    QueryUsage.ParameterUse checkProductCodes(
            Basis basis, Plan plan, String question, QueryProtocol.Memory memory, List<Condition> requested) {
        boolean hasProduct = false;
        boolean published = false;
        var context = new LinkedHashSet<String>();
        // 自然周追问和日期路径共用完整句式，其他商品指代沿用原判断。
        boolean followsProduct = PRODUCT_CONTINUATION.matcher(question).find()
                || keepsOtherConditions(question)
                || QueryDates.previousWeekFollowup(question);
        var fixed = provenFixedFilters(basis, plan);
        for (var filter : plan.filters()) {
            // 只处理被确认为商品编码的列，其他等值业务条件沿用原有验证。
            String label = basis.labels().get(filter.field());
            boolean code = isProductCode(label);
            boolean id = label != null && PRODUCT_ID_LABEL.matcher(label).find();
            if (!code && !id) {
                continue;
            }
            hasProduct |= !filter.excluded();
            if (requested.contains(filter)) {
                // 已按原话和发布字段核实，排除某个 SKU 本身不锁定单个商品。
                continue;
            }
            if (fixed.contains(filter)) {
                // 实际计划只在与确认固定值完全相符时采用发布依据。
                published = true;
                continue;
            }
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
                || !QueryCorpus.isDateField(basis, selected.field())
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

    /** 日期解析失败不能变成数据库的隐式日期转换。 */
    private LocalDate parseDay(String text) {
        try {
            return LocalDate.parse(text);
        } catch (java.time.DateTimeException invalid) {
            throw QueryFailure.invalidParameter();
        }
    }

    /** 编译有限读操作，并按已确认指标及所有实际依赖检查口径。 */
    Compiled compile(Basis basis, Plan proposal, boolean mysql, String database) {
        var plan = applyMetricFilters(basis, proposal);
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
                        .thenComparing(QueryProtocol.Condition::value)
                        .thenComparing(QueryProtocol.Condition::excluded))
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
            var rules = metricRules(basis, metric, plan.metric());
            if ("BUSINESS_DAY".equals(rules.getDateMode())) {
                // 即使调用者绕过日期解析，编译也不能生成缺少确认业务日的 SQL。
                var date = basis.fields().get(rules.getDateFieldId());
                if (plan.time() == null
                        ? filters.stream().noneMatch(filter -> date.equals(filter.field()))
                        : !date.equals(plan.time().field())) {
                    throw QueryFailure.knowledge();
                }
            }
            if ("COLUMN".equals(rules.getUnitMode())) {
                // 同行币种值必须一致，实际 SQL 负责提供单位核验列。
                unitField = basis.fields().get(rules.getUnitFieldId());
                projection.add(unitField);
                usage.field(unitField, "UNIT_CHECK");
            } else if ("FIXED".equals(rules.getUnitMode())) {
                // 已确认固定单位优先，不由模型重新提议。
                unit = rules.getUnit();
            }
            meaning = metric.getContent().path("meaning").asText()
                    + metric.getContent().path("usage").asText();
            columns.add(new QueryColumnVO("value", metric.getLabel()));
        } else {
            // 明细由确切的已确认字段集合决定，禁止星号和模型别名。
            plan.fields().forEach(f -> columns.add(new QueryColumnVO(f.column(), f.column())));
        }

        var time = plan.time();
        boolean trend = time != null && "TREND".equals(time.mode());
        if (time != null) {
            if (!QueryCorpus.isDateField(basis, time.field())) {
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
            // SUM、差额和及 COUNT DISTINCT 都忽略空操作数，单位校验使用相同的参与行。
            var identifier = sqlField(unitField, aliases, mysql);
            var contributing =
                    selected.stream().map(field -> field + " IS NOT NULL").collect(Collectors.joining(" AND "));
            var contribution = "CASE WHEN " + contributing + " THEN ";
            var unitValue = contribution + identifier + " END";
            // 字节顺序能区别大小写不同的计量单位，完整性单独核算空值。
            var exactUnit = mysql ? "CAST(" + unitValue + " AS BINARY)" : "(" + unitValue + ") COLLATE \"C\"";
            select += ", MIN(" + unitValue + "), MIN(" + exactUnit + ") = MAX(" + exactUnit + "), COUNT(" + unitValue
                    + ") = COUNT(" + contribution + "1 END)";
        }
        var sql = new StringBuilder("SELECT ").append(select).append(" FROM ");
        var first = required.iterator().next();
        sql.append(sqlTable(basis, first, mysql, database)).append(' ').append(aliases.get(first));
        var path = new QueryJoinPath().select(basis, required, first, plan, filters);
        join(basis, path, aliases, mysql, database, sql, usage);
        var joinKeys = path.values().stream()
                .flatMap(relation -> relation.keys().stream())
                .map(pair -> Set.of(pair.left(), pair.right()))
                .collect(Collectors.toUnmodifiableSet());

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
            usage.field(field, "FILTER");
            predicates.add(sqlField(field, aliases, mysql) + (filter.excluded() ? " <> ?" : " = ?"));
            var argument = bindFilter(basis, filter).value();
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
                usage.finish(aliases.keySet()),
                joinKeys);
    }

    /** 聚合必须匹配发布中已确认的指标公式。 */
    GovernanceFactVO metric(Basis basis, Plan plan) {
        Integer arity = ARITY.get(plan.operation());
        if (arity == null || plan.fields() == null || plan.fields().size() != arity) {
            throw QueryFailure.knowledge();
        }
        var metrics = basis.facts().values().stream()
                .filter(f -> "METRIC".equals(f.getTarget().getKind()))
                .toList();
        var named =
                metrics.stream().filter(f -> f.getLabel().equals(plan.metric())).toList();
        var matches = (named.isEmpty() ? metrics : named)
                .stream()
                        .filter(f -> matchesFormula(
                                basis,
                                plan,
                                MetricFormula.parse(f.getContent().path("rule").asText())))
                        .toList();
        if (matches.size() != 1) {
            // 指定了已确认的指标时，不用同片段里的其他指标替代它的计算口径。
            throw QueryFailure.knowledge();
        }
        return matches.get(0);
    }

    /**
     * 公式可写真实模式名，运算顺序和实际目录列须与计划一致。
     */
    private boolean matchesFormula(Basis basis, Plan plan, MetricFormula formula) {
        if (formula == null || !plan.operation().equals(formula.operation())) {
            // 只比较通过治理公式语法核对的同类运算。
            return false;
        }
        for (int i = 0; i < plan.fields().size(); i++) {
            // 以确切目录中的模式和物理列核对当前运算位置。
            var field = plan.fields().get(i);
            var reference = formula.fields().get(i);
            var logical = field.table() + "." + field.column();
            if (reference.equalsIgnoreCase(logical)) {
                // 逻辑表名已由当前候选唯一确定，无须再补模式。
                continue;
            }
            var table = basis.tables().get(field.table());
            if (table == null) {
                // 不能通过片段外的模式或同名表匹配公式。
                return false;
            }
            var schema = table.entry().observation().structure().object().schema();
            if (schema == null || !reference.equalsIgnoreCase(schema + "." + logical)) {
                // 有模式限定时只接受目录中的实际模式。
                return false;
            }
        }
        return true;
    }

    /**
     * 按已核实的连接顺序生成 SQL，记录全部实际参与连接的对象与字段。
     */
    private void join(
            Basis basis,
            Map<String, Relation> path,
            Map<String, String> aliases,
            boolean mysql,
            String database,
            StringBuilder sql,
            QueryUsage.Capture usage) {
        for (var step : path.entrySet()) {
            // 保持原计划表的别名，中间表只在 JOIN 时分配别名。
            var next = step.getKey();
            var relation = step.getValue();
            var alias = aliases.computeIfAbsent(next, name -> "t" + aliases.size());
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
                    .append(alias)
                    .append(" ON ")
                    .append(conditions);
        }
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

    /** 只拼接数据库返回的真实库／模式和表标识。 */
    private String sqlTable(Basis basis, String name, boolean mysql, String database) {
        var object = basis.tables().get(name).entry().observation().structure().object();
        if (!QueryCorpus.ordinaryTable(basis, name)) {
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
    private static Object parameter(String type, String value) {
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
