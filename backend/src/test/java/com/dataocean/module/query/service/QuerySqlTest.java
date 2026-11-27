package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dataocean.module.datasource.mapper.CatalogMapper.Observation;
import com.dataocean.module.datasource.mapper.GovernanceCatalogMapper.Column;
import com.dataocean.module.datasource.service.GovernanceCatalog.Entry;
import com.dataocean.module.datasource.source.SourceMetadata.Key;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;
import com.dataocean.module.datasource.source.SourceMetadata.Structure;
import com.dataocean.module.governance.entity.dto.MetricFilterDTO;
import com.dataocean.module.governance.entity.dto.MetricRulesDTO;
import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
import com.dataocean.module.governance.entity.vo.GovernanceTargetVO;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;
import com.dataocean.module.query.entity.vo.QueryColumnVO;
import com.dataocean.module.query.service.QueryCorpus.Basis;
import com.dataocean.module.query.service.QueryCorpus.Table;
import com.dataocean.module.query.service.QueryProtocol.Condition;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.Memory;
import com.dataocean.module.query.service.QueryProtocol.MemoryTurn;
import com.dataocean.module.query.service.QueryProtocol.Plan;
import com.dataocean.module.query.service.QueryProtocol.TimeSelection;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 校验首条库存场景中暴露的双列运算偏差及实际目录边界。
 */
class QuerySqlTest {

    /** 双列提议只有匹配已确认的相减公式才可以绑定并生成 SQL。 */
    @Test
    void normalizesAndCompilesVerifiedDifference() {
        var stock = new Field("stock_snapshot", "on_hand");
        var locked = new Field("stock_snapshot", "locked");
        var date = new Field("stock_snapshot", "business_date");
        var proposal = new Plan(
                "READY",
                null,
                "1",
                "SUM",
                "日末可售量",
                "箱",
                List.of(stock, locked),
                List.of(new Condition(date, "2026-11-20")));
        var metricBody = JsonMapper.builder()
                .build()
                .readTree(
                        """
                {"rule":"SUM(stock_snapshot.on_hand - stock_snapshot.locked)",
                 "meaning":"实际在库扣除锁定。","usage":"不计在途。",
                 "metricRules":{"dateMode":"NONE","fixedFilters":[],"unitMode":"FIXED","unit":"箱"}}
                """);
        var metric = new GovernanceFactVO(
                new GovernanceTargetVO("1", "METRIC", "8"), "9", 1, "10", "日末可售量", metricBody, List.of());
        var plan = QuerySql.normalize(proposal);
        var result = new QuerySql().compile(basis("TABLE", stock, locked, date, metric), plan, true, "warehouse");
        assertThat(result.sql())
                .isEqualTo(
                        "SELECT SUM(t0.`on_hand` - t0.`locked`) FROM `warehouse`.`stock_snapshot` t0 WHERE t0.`business_date` = ?");
        assertThat(result.parameters()).containsExactly(LocalDate.of(2026, 11, 20));
        assertThat(result.scope()).contains("2026-11-20", "不计在途");
        assertThat(result.unit()).isEqualTo("箱");
        // 使用证据应来自最终表达式和实际筛选，保留确切列修订。
        var usage = result.usage();
        assertThat(usage.objects())
                .containsExactly(new QueryUsage.ObjectRef(
                        "1",
                        1,
                        "10",
                        List.of(
                                new QueryUsage.ColumnRef("8", "9", List.of("COMPUTE")),
                                new QueryUsage.ColumnRef("10", "11", List.of("COMPUTE")),
                                new QueryUsage.ColumnRef("6", "7", List.of("FILTER")))));
        assertThat(usage.facts()).containsExactly(new QueryUsage.FactRef("METRIC", "1", "8", "9"));
        assertThatThrownBy(() ->
                        new QuerySql().compile(basis("VIEW", stock, locked, date, metric), plan, true, "warehouse"))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("OUT_OF_SCOPE");
    }

    /** 相同等值条件调整顺序或小数写法后，使用同样的 SQL 与实际绑定值。 */
    @Test
    void normalizesEquivalentSqlParameters() {
        var stock = new Field("stock_snapshot", "on_hand");
        var locked = new Field("stock_snapshot", "locked");
        var date = new Field("stock_snapshot", "business_date");
        var context = basis("TABLE", stock, locked, date, null);
        var compiler = new QuerySql();
        var first = new Plan(
                "READY",
                null,
                "1",
                "VALUES",
                null,
                null,
                List.of(stock),
                List.of(new Condition(stock, "20.00"), new Condition(date, "2026-11-20")));
        var second = new Plan(
                "READY",
                null,
                "1",
                "VALUES",
                null,
                null,
                List.of(stock),
                List.of(new Condition(date, "2026-11-20"), new Condition(stock, "20")));

        var a = compiler.compile(context, first, true, "warehouse");
        var b = compiler.compile(context, second, true, "warehouse");
        assertThat(a.sql()).isEqualTo(b.sql());
        assertThat(a.parameters()).isEqualTo(b.parameters());
    }

    /** 类型错误或过大的数量级都必须在 JDBC 许可前识别。 */
    @ParameterizedTest
    @ValueSource(strings = {"SKU-1001", "1e200"})
    void detectsIncompatibleNumericParameters(String value) {
        var stock = new Field("stock_snapshot", "on_hand");
        var locked = new Field("stock_snapshot", "locked");
        var date = new Field("stock_snapshot", "business_date");
        var plan = new Plan(
                "READY", null, "1", "VALUES", null, null, List.of(stock), List.of(new Condition(stock, value)));

        assertThatThrownBy(() ->
                        new QuerySql().compile(basis("TABLE", stock, locked, date, null), plan, true, "warehouse"))
                .isInstanceOfSatisfying(QueryFailure.class, failure -> assertThat(failure.parameterMismatch())
                        .isTrue());
    }

    /** 业务日默认从原请求锚点取值，显式时间和不同统计口径仍独立。 */
    @Test
    void usesCurrentBusinessDayForPointAndSevenDaysForTrend() {
        var stock = new Field("stock_snapshot", "on_hand");
        var locked = new Field("stock_snapshot", "locked");
        var date = new Field("stock_snapshot", "business_date");
        var definition = JsonMapper.builder()
                .build()
                .readTree(
                        """
                {"rule":"SUM(stock_snapshot.on_hand - stock_snapshot.locked)",
                 "meaning":"实际在库扣除锁定。","usage":"按业务日期统计。",
                 "metricRules":{"dateMode":"BUSINESS_DAY","dateFieldId":"6","fixedFilters":[],"unitMode":"NONE"}}
                """);
        var fact = new GovernanceFactVO(
                new GovernanceTargetVO("1", "METRIC", "8"),
                "9",
                1,
                "10",
                "日末可售量",
                definition,
                List.of(new GovernanceTargetVO("1", "COLUMN", "6")));
        var evidence = basis("TABLE", stock, locked, date, fact);
        var compiler = new QuerySql();
        var today = LocalDate.of(2026, 11, 22);
        var point = new Plan("READY", null, "1", "SUM_DIFFERENCE", "日末可售量", null, List.of(stock, locked), List.of());
        var implicit = compiler.resolveTime(evidence, point, today, "SKU-1001 可售库存有多少？", false);
        var one = compiler.compile(evidence, implicit, true, "warehouse");
        assertThat(one.parameters()).containsExactly(today);
        assertThat(one.scope()).contains("默认当前业务日");
        assertThat(one.series()).isNull();

        var requested = new Plan(
                "READY",
                null,
                "1",
                "SUM_DIFFERENCE",
                "日末可售量",
                null,
                List.of(stock, locked),
                List.of(),
                new TimeSelection(date, "TREND", "2026-11-14", "2026-11-20", false));
        var trend = compiler.resolveTime(evidence, requested, today, "看 SKU-1001 的库存每日趋势", false);
        var chart = compiler.compile(evidence, trend, true, "warehouse");
        assertThat(chart.parameters()).containsExactly(today.minusDays(6), today);
        assertThat(chart.sql()).contains("BETWEEN ? AND ?", "GROUP BY", "ORDER BY");
        assertThat(chart.series().getStart()).isEqualTo("2026-11-16");

        var specified = new Plan(
                "READY",
                null,
                "1",
                "SUM_DIFFERENCE",
                "日末可售量",
                null,
                List.of(stock, locked),
                List.of(new Condition(date, "2026-11-20")));
        var dateFromUser = compiler.resolveTime(evidence, specified, today, "只查 2026-11-20 日末库存", false);
        var exact = compiler.compile(evidence, dateFromUser, true, "warehouse");
        assertThat(exact.parameters()).containsExactly(LocalDate.of(2026, 11, 20));

        // 单个完整日期也约束没有历史依赖的计划，不能沿用模型旧值。
        var corrected = compiler.resolveTime(evidence, specified, today, "只查 2026-11-19 日末库存", false);
        assertThat(compiler.compile(evidence, corrected, true, "warehouse").parameters())
                .containsExactly(LocalDate.of(2026, 11, 19));

        // SSE 或模型节点重用旧提议时，样例中的日期仍无法变成查询条件。
        var namesOnly = compiler.resolveTime(evidence, specified, today, "原味燕麦奶 1 L 的可售库存是多少箱？", false);
        assertThat(compiler.compile(evidence, namesOnly, true, "warehouse").parameters())
                .containsExactly(today);
        var reused = compiler.resolveTime(evidence, specified, today, "沿用刚才的日期，查可售库存", true);
        assertThat(compiler.compile(evidence, reused, true, "warehouse").parameters())
                .containsExactly(LocalDate.of(2026, 11, 20));
        var relative = compiler.resolveTime(evidence, specified, today, "上周五的可售库存还有多少？", false);
        assertThat(compiler.compile(evidence, relative, true, "warehouse").parameters())
                .containsExactly(LocalDate.of(2026, 11, 13));
    }

    /**
     * 名称推导出的商品码要有可信来源，避免把相邻规格或数据库示例当成查数条件。
     */
    @Test
    void acceptsPublishedButRejectsInventedProductCode() {
        var code = new Field("product", "code");
        var evidence = new Basis(
                null,
                null,
                0,
                "DEMO-1 是中杯柠檬茶。商品 DEMO-20 还有别的规格。",
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(code, "SKU 编码"));
        var compiler = new QuerySql();
        var memory = new Memory("2026-11-22T00:00:00Z", List.of(), List.of());
        var published =
                new Plan("READY", null, "1", "SUM", "库存", null, List.of(code), List.of(new Condition(code, "DEMO-1")));
        var guessed =
                new Plan("READY", null, "1", "SUM", "库存", null, List.of(code), List.of(new Condition(code, "DEMO-2")));
        compiler.checkProductCodes(evidence, published, "中杯柠檬茶库存", memory);
        compiler.checkProductCodes(evidence, guessed, "查 DEMO-2 的库存", memory);
        assertThatThrownBy(() -> compiler.checkProductCodes(evidence, guessed, "大杯柠檬茶库存", memory))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("KNOWLEDGE_INSUFFICIENT");
    }

    /**
     * 纯日期追问使用上轮业务日跨月移动，同一口径保留其他条件，拒绝从多日走势猜基准。
     */
    @Test
    void followsOnlyOneConfirmedDateInTheSameMetric() {
        var stock = new Field("stock_snapshot", "on_hand");
        var locked = new Field("stock_snapshot", "locked");
        var date = new Field("stock_snapshot", "business_date");
        var basis = basis("TABLE", stock, locked, date, null);
        var compiler = new QuerySql();
        var fixed = new Plan(
                "READY",
                null,
                "1",
                "SUM",
                "可售量",
                null,
                List.of(stock),
                List.of(new Condition(stock, "5"), new Condition(date, "2026-11-01")));
        var shifted = compiler.followBusinessDay(basis, fixed, fixed, -1);
        assertThat(shifted.time()).isEqualTo(new TimeSelection(date, "DAY", "2026-10-31", "2026-10-31", false));
        assertThat(shifted.filters()).containsExactly(new Condition(stock, "5"));
        assertThat(compiler.keepsBusinessDay("换成另一个规格，其他条件照旧。")).isTrue();
        assertThat(compiler.keepsBusinessDay("其他条件照旧，但改成昨天。")).isFalse();
        var series = new Plan(
                "READY",
                null,
                "1",
                "SUM",
                "可售量",
                null,
                List.of(stock),
                List.of(),
                new TimeSelection(date, "TREND", "2026-11-01", "2026-11-04", false));
        assertThat(compiler.followBusinessDay(basis, fixed, series, -1)).isNull();
    }

    /** 上周追问沿用已验证的原计划，按业务自然周区间逐日执行。 */
    @Test
    void keepsConfirmedFiltersWhenFollowingPreviousCalendarWeek() {
        var stock = new Field("stock_snapshot", "on_hand");
        var locked = new Field("stock_snapshot", "locked");
        var date = new Field("stock_snapshot", "business_date");
        var evidence = basis("TABLE", stock, locked, date, null);
        var previous = new Plan(
                "READY",
                null,
                "1",
                "SUM",
                "库存",
                null,
                List.of(stock),
                List.of(new Condition(stock, "5"), new Condition(date, "2026-11-19")),
                new TimeSelection(date, "DAY", "2026-11-19", "2026-11-19", false));

        var week = new QuerySql().followPreviousWeek(evidence, previous, LocalDate.of(2026, 11, 24));

        assertThat(week.time()).isEqualTo(new TimeSelection(date, "TREND", "2026-11-16", "2026-11-22", false));
        assertThat(week.filters()).containsExactly(new Condition(stock, "5"));
        assertThat(week.metric()).isEqualTo(previous.metric());
    }

    /** 已确认的指标名称与计算式要同时匹配，同片段的近义指标不能代替。 */
    @Test
    void rejectsFormulaOfAnotherMetricWhenTheRequestedNameIsKnown() {
        var stock = new Field("stock_snapshot", "on_hand");
        var locked = new Field("stock_snapshot", "locked");
        var date = new Field("stock_snapshot", "business_date");
        var json = JsonMapper.builder().build();
        var available = new GovernanceFactVO(
                new GovernanceTargetVO("1", "METRIC", "8"),
                "9",
                1,
                "10",
                "可售库存",
                json.readTree(
                        """
                        {"rule":"SUM(stock_snapshot.on_hand - stock_snapshot.locked)",
                         "metricRules":{"dateMode":"NONE","fixedFilters":[],"unitMode":"NONE"}}
                        """),
                List.of());
        var onHand = new GovernanceFactVO(
                new GovernanceTargetVO("1", "METRIC", "9"),
                "10",
                1,
                "10",
                "实际在库",
                json.readTree(
                        """
                        {"rule":"SUM(stock_snapshot.on_hand)","metricRules":{"dateMode":"NONE","fixedFilters":[],"unitMode":"NONE"}}
                        """),
                List.of());
        var section = basis("TABLE", stock, locked, date, available);
        var evidence = new Basis(
                section.source(),
                section.binding(),
                section.ordinal(),
                section.text(),
                section.tables(),
                section.fields(),
                Map.of("8", available, "9", onHand),
                section.labels());
        var compiler = new QuerySql();
        var mismatch = new Plan("READY", null, "1", "SUM", "可售库存", null, List.of(stock), List.of());
        var alias = new Plan("READY", null, "1", "SUM", "实际在库总量", null, List.of(stock), List.of());

        assertThatThrownBy(() -> compiler.compile(evidence, mismatch, true, "warehouse"))
                .isInstanceOf(QueryFailure.class);
        assertThat(compiler.compile(evidence, alias, true, "warehouse")
                        .columns()
                        .get(0)
                        .getLabel())
                .isEqualTo("实际在库");
    }

    /** 日期解析与商品校验对「上周」使用同一组纯追问表达。 */
    @ParameterizedTest
    @ValueSource(strings = {"上周？", "那么上个星期？", "那上周呢？"})
    void acceptsConfirmedProductForAllPreviousWeekFollowups(String question) {
        var code = new Field("product", "code");
        var evidence = new Basis(null, null, 0, "", Map.of(), Map.of(), Map.of(), Map.of(code, "SKU 编码"));
        var plan =
                new Plan("READY", null, "1", "SUM", "库存", null, List.of(code), List.of(new Condition(code, "DEMO-2")));
        var memory = new Memory(
                "2026-11-24T00:00:00Z", List.of(new MemoryTurn("confirmed", "查 DEMO-2 的库存", null, plan)), List.of());

        assertThat(QueryDates.previousWeekFollowup(question)).isTrue();
        assertThat(new QuerySql()
                        .checkProductCodes(evidence, plan, question, memory)
                        .contextRequestIds())
                .containsExactly("confirmed");
        assertThatThrownBy(() -> new QuerySql().checkProductCodes(evidence, plan, question + "另查其他商品。", memory))
                .isInstanceOf(QueryFailure.class);
    }

    /**
     * 跨日去重的存在性指标不因为模型添加了示例日期而误缩小到当前业务日。
     */
    @Test
    void leavesCrossDayCountUnboundedWhenUserOmitsDate() {
        var stock = new Field("stock_snapshot", "on_hand");
        var locked = new Field("stock_snapshot", "locked");
        var date = new Field("stock_snapshot", "business_date");
        var definition = JsonMapper.builder()
                .build()
                .readTree(
                        """
                {"rule":"COUNT(DISTINCT stock_snapshot.on_hand)",
                 "meaning":"统计有快照的唯一键。","usage":"跨日或跨仓出现的同一商品只计一次。",
                 "metricRules":{"dateMode":"NONE","fixedFilters":[],"unitMode":"NONE"}}
                """);
        var metric = new GovernanceFactVO(
                new GovernanceTargetVO("1", "METRIC", "8"),
                "9",
                1,
                "10",
                "有快照的商品数",
                definition,
                List.of(new GovernanceTargetVO("1", "COLUMN", "6")));
        var basis = basis("TABLE", stock, locked, date, metric);
        var plan = new Plan(
                "READY",
                null,
                "1",
                "COUNT_DISTINCT",
                "有快照的商品数",
                null,
                List.of(stock),
                List.of(new Condition(date, "2026-11-20")),
                new TimeSelection(date, "DAY", "2026-11-20", "2026-11-20", false));
        var resolved =
                new QuerySql().resolveTime(basis, plan, LocalDate.of(2026, 11, 22), "换个问题。常温饮料中，有库存快照的商品有多少种？", false);
        assertThat(resolved.filters()).isEmpty();
        assertThat(resolved.time()).isNull();
    }

    /**
     * PostgreSQL 以实际 Schema 限定行业表，既保留标识大小写，也不拼接用户业务值。
     */
    @Test
    void quotesPostgresSchemaAndBindsOpaqueRouteName() {
        var field = new Field("Parcel Log", "Route\"Key");
        var ref = new ObjectRef("logistics", "delivery_2026", "Parcel Log", "TABLE", null, null, null);
        var structure = new Structure(ref, List.of(), List.of(), List.of(), List.of());
        var column = new Column(7, 8, field.column(), "character varying", false, "", 0);
        var observation = new Observation(1, 1, 1L, 10L, null, null, null, structure, null);
        var table = new Table(new Entry(1, 1, false, observation, List.of(column)), Map.of());
        var basis = new Basis(null, null, 0, "", Map.of(field.table(), table), Map.of(), Map.of(), Map.of(field, "线路"));
        var plan = new Plan(
                "READY",
                null,
                "7",
                "VALUES",
                null,
                null,
                List.of(field),
                List.of(new Condition(field, "North's route")));
        var compiled = new QuerySql().compile(basis, plan, false, "logistics");
        assertThat(compiled.sql())
                .isEqualTo(
                        "SELECT t0.\"Route\"\"Key\" FROM \"delivery_2026\".\"Parcel Log\" t0 WHERE t0.\"Route\"\"Key\" = ?");
        assertThat(compiled.parameters()).containsExactly("North's route");
    }

    /**
     * 区域筛选可以通过已确认网点关联限定订单，网点字段只进入复合连接和血缘。
     */
    @Test
    void joinsOutletWithoutAddingItsFieldsToOrderAmountProjection() {
        var basis = orderBasis(
                relation(
                        "81",
                        """
                        {"pairs":[{"left":"11","right":"21"},{"left":"12","right":"22"}]}
                        """),
                relation(
                        "82",
                        """
                        {"pairs":[{"left":"31","right":"23"}]}
                        """),
                relation(
                        "83",
                        """
                        {"pairs":[{"left":"23","right":"41"}]}
                        """));
        var compiled = new QuerySql().compile(basis, orderRegionPlan(), true, "sales");

        assertThat(compiled.sql())
                .isEqualTo("SELECT SUM(t0.`amount`) FROM `sales`.`orders` t0"
                        + " JOIN `sales`.`outlet` t2 ON t0.`outlet_id` = t2.`id` AND t0.`tenant_id` = t2.`tenant_id`"
                        + " JOIN `sales`.`region` t1 ON t1.`id` = t2.`region_id` WHERE t1.`name` = ?");
        assertThat(compiled.parameters()).containsExactly("华东区");
        assertThat(compiled.columns()).containsExactly(new QueryColumnVO("value", "订单金额"));
        assertThat(compiled.projection()).containsExactly(new Field("orders", "amount"));

        // 中间表和两个关系修订进入执行血缘，不使用只有旁支关系的仓库。
        assertThat(compiled.usage().objects())
                .extracting(QueryUsage.ObjectRef::objectId)
                .containsExactly("1", "3", "2");
        assertThat(compiled.usage().objects().get(2).columns())
                .containsExactly(
                        new QueryUsage.ColumnRef("21", "21", List.of("JOIN")),
                        new QueryUsage.ColumnRef("22", "22", List.of("JOIN")),
                        new QueryUsage.ColumnRef("23", "23", List.of("JOIN")));
        assertThat(compiled.usage().facts())
                .filteredOn(fact -> "RELATION".equals(fact.kind()))
                .extracting(QueryUsage.FactRef::targetId)
                .containsExactly("81", "82");
    }

    /**
     * 已确认的直接关联足以满足查询时，继续使用原来的两表 SQL。
     */
    @Test
    void keepsTheDirectOrderRegionJoin() {
        var basis = orderBasis(
                relation(
                        "81",
                        """
                        {"pairs":[{"left":"11","right":"21"}]}
                        """),
                relation(
                        "82",
                        """
                        {"pairs":[{"left":"23","right":"31"}]}
                        """),
                relation(
                        "83",
                        """
                        {"pairs":[{"left":"14","right":"31"}]}
                        """));
        var compiled = new QuerySql().compile(basis, orderRegionPlan(), true, "sales");

        assertThat(compiled.sql())
                .isEqualTo("SELECT SUM(t0.`amount`) FROM `sales`.`orders` t0"
                        + " JOIN `sales`.`region` t1 ON t0.`region_id` = t1.`id` WHERE t1.`name` = ?");
        assertThat(compiled.usage().objects())
                .extracting(QueryUsage.ObjectRef::objectId)
                .containsExactly("1", "3");
    }

    /**
     * 缺失复合键时拒绝残缺路径，有两条已确认路径时也不能按长短挑选。
     */
    @Test
    void rejectsIncompleteOrAmbiguousOrderRegionPaths() {
        var orderOutlet =
                relation("81", """
                {"pairs":[{"left":"11","right":"21"}]}
                """);
        var outletRegion =
                relation("82", """
                {"pairs":[{"left":"23","right":"31"}]}
                """);
        var incomplete = orderBasis(
                orderOutlet,
                relation(
                        "82",
                        """
                {"pairs":[{"left":"23","right":"31"},{"left":"22","right":"99"}]}
                """));
        var ambiguous = orderBasis(
                orderOutlet,
                outletRegion,
                relation(
                        "83",
                        """
                        {"pairs":[{"left":"23","right":"41"}]}
                        """),
                relation(
                        "84",
                        """
                        {"pairs":[{"left":"42","right":"31"}]}
                        """));

        // 两种证据均不能生成可派发的 SQL。
        for (var basis : List.of(incomplete, ambiguous)) {
            assertThatThrownBy(() -> new QuerySql().compile(basis, orderRegionPlan(), true, "sales"))
                    .isInstanceOf(QueryFailure.class)
                    .hasMessage("KNOWLEDGE_INSUFFICIENT");
        }
    }

    /**
     * 只连接复合主键的一部分，或依赖隐式字符串转换时，不能证明每笔订单只累计一次。
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"pairs\":[{\"left\":\"11\",\"right\":\"21\"}]}",
                "{\"pairs\":[{\"left\":\"15\",\"right\":\"21\"},{\"left\":\"12\",\"right\":\"22\"}]}"
            })
    void rejectsUnprovenAggregationGrainAcrossIntermediateTable(String keys) {
        var basis =
                orderBasis(relation("81", keys), relation("82", "{\"pairs\":[{\"left\":\"23\",\"right\":\"31\"}]}"));
        var explicitOutlet = orderRegionPlan(new Condition(new Field("outlet", "id"), "10"));

        for (var plan : List.of(orderRegionPlan(), explicitOutlet)) {
            // 显式筛选引用中间表时仍需证明这条实际联接不会扩大求和粒度。
            assertThatThrownBy(() -> new QuerySql().compile(basis, plan, true, "sales"))
                    .isInstanceOf(QueryFailure.class)
                    .hasMessage("KNOWLEDGE_INSUFFICIENT");
        }
    }

    /**
     * 视图旁路不能挡住普通表路径，也不能在普通表路径缺失时作为中间表使用。
     */
    @Test
    void ignoresViewAlternativesWhenResolvingIntermediateTables() {
        var orderOutlet =
                relation("81", "{\"pairs\":[{\"left\":\"11\",\"right\":\"21\"},{\"left\":\"12\",\"right\":\"22\"}]}");
        var outletRegion = relation("82", "{\"pairs\":[{\"left\":\"23\",\"right\":\"31\"}]}");
        var orderView = relation("83", "{\"pairs\":[{\"left\":\"11\",\"right\":\"41\"}]}");
        var viewRegion = relation("84", "{\"pairs\":[{\"left\":\"42\",\"right\":\"31\"}]}");
        var compiler = new QuerySql();
        var plan = orderRegionPlan();

        assertThat(compiler.compile(
                                orderBasis("VIEW", orderOutlet, outletRegion, orderView, viewRegion),
                                plan,
                                true,
                                "sales")
                        .sql())
                .isEqualTo(compiler.compile(orderBasis(orderOutlet, outletRegion), plan, true, "sales")
                        .sql());
        assertThatThrownBy(() -> compiler.compile(orderBasis("VIEW", orderView, viewRegion), plan, true, "sales"))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("KNOWLEDGE_INSUFFICIENT");
    }

    /**
     * 同表不同区域键不能由重排决定。反向书写同一等式的候选仍等价。
     */
    @Test
    void selectsEquivalentJoinKeysButRejectsConflictingKeysInEitherOrder() {
        var orderOutlet =
                relation("81", "{\"pairs\":[{\"left\":\"11\",\"right\":\"21\"},{\"left\":\"12\",\"right\":\"22\"}]}");
        var compiler = new QuerySql();
        var plan = orderRegionPlan();
        var current = compiler.compile(
                orderBasis(orderOutlet, relation("82", "{\"pairs\":[{\"left\":\"23\",\"right\":\"31\"}]}")),
                plan,
                true,
                "sales");
        var equivalent = compiler.compile(
                orderBasis(orderOutlet, relation("83", "{\"pairs\":[{\"left\":\"31\",\"right\":\"23\"}]}")),
                plan,
                true,
                "sales");
        var historical = compiler.compile(
                orderBasis(orderOutlet, relation("84", "{\"pairs\":[{\"left\":\"24\",\"right\":\"31\"}]}")),
                plan,
                true,
                "sales");

        assertThat(QueryTableSelection.select(List.of(equivalent, current), Set.of()))
                .hasValue(0);
        for (var candidates : List.of(List.of(current, historical), List.of(historical, current))) {
            // 无论哪个不同口径先召回都应拦住派发。
            assertThatThrownBy(() -> QueryTableSelection.select(candidates, Set.of()))
                    .isInstanceOf(QueryFailure.class)
                    .hasMessage("KNOWLEDGE_INSUFFICIENT");
        }
    }

    /**
     * 日期追问只使用原关联修订，混有其他区域键的片段也按原键编译。
     */
    @Test
    void retainsOnlyOriginalRelationRevisionsForDateFollowup() {
        var orderOutlet =
                relation("81", "{\"pairs\":[{\"left\":\"11\",\"right\":\"21\"},{\"left\":\"12\",\"right\":\"22\"}]}");
        var currentRegion = relation("82", "{\"pairs\":[{\"left\":\"23\",\"right\":\"31\"}]}");
        var historicalRegion = relation("83", "{\"pairs\":[{\"left\":\"24\",\"right\":\"31\"}]}");
        var revisedRegion = relation("82", "{\"pairs\":[{\"left\":\"24\",\"right\":\"31\"}]}");
        revisedRegion.setRevisionId("92");

        var date = new Field("orders", "business_day");
        var original = orderRegionPlan(new Condition(date, "2026-11-25"));
        var compiler = new QuerySql();
        var previous = compiler.compile(orderBasis(orderOutlet, currentRegion), original, true, "sales");
        var usage = previous.usage();

        assertThat(usage.historicalBasis(orderBasis(orderOutlet, historicalRegion), original))
                .isNull();
        assertThat(usage.historicalBasis(orderBasis(orderOutlet, revisedRegion), original))
                .isNull();
        var mixed = orderBasis(orderOutlet, currentRegion, historicalRegion);
        var fixed = usage.historicalBasis(mixed, original);
        assertThat(fixed.facts().values()).contains(currentRegion).doesNotContain(historicalRegion);

        var moved = compiler.followBusinessDay(fixed, original, original, -1);
        var plan = compiler.historicalConditions(fixed, moved, original, "那前一天呢？", moved.time());
        var actual = compiler.compile(fixed, plan, true, "sales");
        var other = compiler.compile(orderBasis(orderOutlet, historicalRegion), plan, true, "sales");
        assertThat(usage.sameHistoricalScope(actual.usage())).isTrue();
        assertThat(usage.sameHistoricalScope(other.usage())).isFalse();
        assertThat(actual.joinKeys()).isEqualTo(previous.joinKeys());
        assertThat(actual.parameters()).containsExactly(LocalDate.of(2026, 11, 24), "华东区");
    }

    /**
     * 即使本轮提议使用另一个同名指标，也只在原计算公式上改变明确指定的日期。
     */
    @Test
    void retainsTheOriginalFormulaWhenOnlyTheDateChanges() {
        var orderOutlet =
                relation("81", "{\"pairs\":[{\"left\":\"11\",\"right\":\"21\"},{\"left\":\"12\",\"right\":\"22\"}]}");
        var outletRegion = relation("82", "{\"pairs\":[{\"left\":\"23\",\"right\":\"31\"}]}");
        var current = orderBasis(orderOutlet, outletRegion);
        var gross = new GovernanceFactVO(
                new GovernanceTargetVO("1", "METRIC", "86"),
                "86",
                1,
                "10",
                "订单金额",
                JsonMapper.builder()
                        .build()
                        .readTree(
                                """
                        {"rule":"SUM(orders.gross_amount)","metricRules":{"dateMode":"NONE","fixedFilters":[],"unitMode":"NONE"}}
                        """),
                List.of());
        var facts = new LinkedHashMap<>(current.facts());
        facts.put("86", gross);
        var basis = new Basis(
                current.source(),
                current.binding(),
                current.ordinal(),
                current.text(),
                current.tables(),
                current.fields(),
                facts,
                current.labels());
        var date = new Field("orders", "business_day");
        var original = orderRegionPlan(new Condition(date, "2026-11-25"));
        var proposal = new Plan(
                "READY",
                null,
                "1",
                "SUM",
                "订单金额",
                null,
                List.of(new Field("orders", "gross_amount")),
                orderRegionPlan().filters());
        var compiler = new QuerySql();
        var question = "改查 2026-11-24，其他条件不变";
        var changed =
                compiler.changedHistoricalDate(basis, original, proposal, question, null, LocalDate.of(2026, 11, 24));
        var plan = compiler.historicalConditions(basis, proposal, original, question, changed);
        var compiled = compiler.compile(basis, plan, true, "sales");

        assertThat(compiled.sql()).startsWith("SELECT SUM(t0.`amount`)").doesNotContain("gross_amount");
        assertThat(compiled.parameters()).containsExactly(LocalDate.of(2026, 11, 24), "华东区");
        assertThat(compiler.withHistoricalMetric(proposal, original).fields()).isEqualTo(original.fields());
    }

    /**
     * 日期、固定状态和币种必须来自同一份已确认结构，文字说明不能代替。
     */
    @Test
    void enforcesStructuredMetricRulesWithPublishedDependencies() {
        var basis = orderBasis(relation("83", "{\"pairs\":[{\"left\":\"14\",\"right\":\"31\"}]}"));
        var fact = basis.facts().get("80");
        var definition = (ObjectNode) fact.getContent();
        definition.remove("metricRules");
        definition.put("usage", "按业务日期汇总，只统计已支付订单，不同币种不得混加。");
        var compiler = new QuerySql();
        var plan = orderRegionPlan();
        var today = LocalDate.of(2026, 11, 25);
        assertThatThrownBy(() -> compiler.resolveTime(basis, plan, today, "查华东区订单金额", false))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("KNOWLEDGE_INSUFFICIENT");

        var mapper = JsonMapper.builder().build();
        definition.set(
                "metricRules",
                mapper.valueToTree(new MetricRulesDTO("BUSINESS_DAY", "16", List.of(), "NONE", null, null)));
        assertThatThrownBy(() -> compiler.resolveTime(basis, plan, today, "查华东区订单金额", false))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("KNOWLEDGE_INSUFFICIENT");
        // 日期列在目录中存在还不够，也必须属于该指标正式依赖。
        fact.setDependencies(List.of(new GovernanceTargetVO("1", "COLUMN", "16")));
        var dated = compiler.resolveTime(basis, plan, today, "查华东区订单金额", false);
        assertThat(compiler.compile(basis, dated, true, "sales").parameters()).contains(today);
        assertThatThrownBy(() -> compiler.compile(basis, plan, true, "sales"))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("KNOWLEDGE_INSUFFICIENT");

        fact.setDependencies(
                List.of(new GovernanceTargetVO("1", "COLUMN", "18"), new GovernanceTargetVO("1", "COLUMN", "19")));
        definition.set(
                "metricRules",
                mapper.valueToTree(new MetricRulesDTO(
                        "NONE", null, List.of(new MetricFilterDTO("18", "PAID")), "COLUMN", null, "19")));
        var compiled = compiler.compile(basis, plan, true, "sales");
        // 实际支付条件和同币种校验进入 SQL，无需模型重复输出这些条件。
        assertThat(compiled.sql())
                .contains(
                        "t0.`payment_state` = ?",
                        "MIN(CASE WHEN t0.`amount` IS NOT NULL THEN t0.`currency_code` END)",
                        "MIN(CAST(CASE WHEN t0.`amount` IS NOT NULL THEN t0.`currency_code` END AS BINARY))",
                        "COUNT(CASE WHEN t0.`amount` IS NOT NULL THEN 1 END)");
        assertThat(compiled.parameters()).containsExactly("PAID", "华东区");
        assertThat(compiled.verifiesUnit()).isTrue();
        var conflict = orderRegionPlan(new Condition(new Field("orders", "payment_state"), "UNPAID"));
        assertThatThrownBy(() -> compiler.compile(basis, conflict, true, "sales"))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("KNOWLEDGE_INSUFFICIENT");

        // 模型使用逻辑表列，无须按人类说明猜测日期、支付状态和币种。
        fact.setDependencies(List.of(
                new GovernanceTargetVO("1", "COLUMN", "16"),
                new GovernanceTargetVO("1", "COLUMN", "18"),
                new GovernanceTargetVO("1", "COLUMN", "19")));
        definition.set(
                "metricRules",
                mapper.valueToTree(new MetricRulesDTO(
                        "BUSINESS_DAY", "16", List.of(new MetricFilterDTO("18", "PAID")), "COLUMN", null, "19")));
        var binding = mock(Binding.class);
        when(binding.id()).thenReturn(1L);
        var published = new Basis(
                basis.source(),
                binding,
                basis.ordinal(),
                basis.text(),
                basis.tables(),
                basis.fields(),
                basis.facts(),
                basis.labels());
        var evidence = published.snippet().getMetrics().stream()
                .filter(metric -> metric.getMetricRules() != null)
                .findFirst()
                .orElseThrow();
        var rules = evidence.getMetricRules();
        assertThat(rules.dateField()).isEqualTo(new Field("orders", "business_day"));
        assertThat(rules.fixedFilters()).containsExactly(new Condition(new Field("orders", "payment_state"), "PAID"));
        assertThat(rules.unitMode()).isEqualTo("COLUMN");
        assertThat(rules.unitField()).isEqualTo(new Field("orders", "currency_code"));
    }

    /**
     * 仅允许覆盖实际业务日期的关系，缺少日期或复合限制无法证明时不派发。
     */
    @Test
    void validatesPublishedRelationDatesBeforeSelectingAJoinPath() {
        var orderOutlet =
                relation("81", "{\"pairs\":[{\"left\":\"11\",\"right\":\"21\"},{\"left\":\"12\",\"right\":\"22\"}]}");
        var outletRegion = relation("82", "{\"pairs\":[{\"left\":\"23\",\"right\":\"31\"}]}");
        var definition = (ObjectNode) outletRegion.getContent();
        definition.put("usage", "自 2026-11-25 开始生效（含当日）");
        definition.putObject("relationWindow").put("mode", "DATE_RANGE").put("validFrom", "2026-11-25");
        var basis = orderBasis(orderOutlet, outletRegion);
        var compiler = new QuerySql();
        var date = new Field("orders", "business_day");
        var outside = orderRegionPlan(new Condition(date, "2026-11-24"));
        var inside = orderRegionPlan(new Condition(date, "2026-11-25"));
        var trend = new Plan(
                "READY",
                null,
                "1",
                "SUM",
                "订单金额",
                null,
                inside.fields(),
                orderRegionPlan().filters(),
                new TimeSelection(date, "TREND", "2026-11-24", "2026-11-26", false));

        for (var plan : List.of(outside, trend, orderRegionPlan())) {
            // 区间跨过有效起点或没有唯一业务日期时，不能选用这段关联。
            assertThatThrownBy(() -> compiler.compile(basis, plan, true, "sales"))
                    .isInstanceOfSatisfying(QueryFailure.class, failure -> assertThat(failure.gap())
                            .isNull())
                    .hasMessage("KNOWLEDGE_INSUFFICIENT");
        }
        assertThatThrownBy(() -> compiler.compile(basis, outside, true, "sales"))
                .isInstanceOfSatisfying(QueryFailure.class, failure -> {
                    // 已有两段键但一段时间不符，应能据实际业务日期独立核对。
                    var review = failure.joinAssessment();
                    assertThat(review.reason()).isEqualTo("RELATION_NOT_APPLICABLE");
                    assertThat(review.dateField()).isEqualTo(date);
                    assertThat(review.dateBounds()).containsExactly("2026-11-24");
                    assertThat(review.relations()).hasSize(2).anySatisfy(relation -> {
                        // 该段未选用的键和正式适用期来自同一关联修订。
                        assertThat(relation.applicable()).isFalse();
                        assertThat(relation.usage())
                                .isEqualTo(definition.path("usage").asText());
                        assertThat(relation.validFrom()).isEqualTo("2026-11-25");
                        assertThat(relation.keys()).hasSize(1);
                    });
                });
        assertThatThrownBy(() -> compiler.compile(orderBasis(orderOutlet), outside, true, "sales"))
                .isInstanceOfSatisfying(QueryFailure.class, failure -> {
                    // 缺失另一段只提供现有键，不能误报适用期不符。
                    assertThat(failure.joinAssessment().reason()).isEqualTo("RELATION_PATH_UNVERIFIABLE");
                    assertThat(failure.joinAssessment().relations()).hasSize(1);
                });
        assertThat(compiler.compile(basis, inside, true, "sales").sql()).contains("JOIN `sales`.`outlet`");
        definition.remove("relationWindow");
        definition.put("usage", "此关系仅适用于 2026-11-25 起的已付款订单。");
        assertThatThrownBy(() -> compiler.compile(basis, inside, true, "sales"))
                .isInstanceOfSatisfying(QueryFailure.class, failure -> {
                    // 缺失适用规则指向具体关联，不依赖本次日期文字。
                    assertThat(failure.diagnostic()).isEqualTo("RELATION_APPLICABILITY_UNVERIFIABLE");
                    assertThat(failure.gap())
                            .isEqualTo(new QueryFailure.Gap("RELATION_APPLICABILITY", "82", List.of()));
                });

        definition.put("usage", "2026 年 11 月 25 日以后才能用于区域归属。");
        for (var plan : List.of(outside, inside)) {
            // 无结构化窗口时，正文日期不能用于选择关联。
            assertThatThrownBy(() -> compiler.compile(basis, plan, true, "sales"))
                    .isInstanceOf(QueryFailure.class)
                    .hasMessage("KNOWLEDGE_INSUFFICIENT");
        }
        var nextDay = orderRegionPlan(new Condition(date, "2026-11-26"));
        assertThatThrownBy(() -> compiler.compile(basis, nextDay, true, "sales"))
                .isInstanceOfSatisfying(QueryFailure.class, failure -> assertThat(failure.diagnostic())
                        .isEqualTo("RELATION_APPLICABILITY_UNVERIFIABLE"));
        definition.put("usage", "此关系仅适用于 2026 年 11 月 25 日起的订单，较早记录不得使用该关系归属区域另限已支付。");
        assertThatThrownBy(() -> compiler.compile(basis, nextDay, true, "sales"))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("KNOWLEDGE_INSUFFICIENT");
        definition.put("usage", "２０２６年１１月２５日以后才能用于区域归属。");
        assertThatThrownBy(() -> compiler.compile(basis, outside, true, "sales"))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("KNOWLEDGE_INSUFFICIENT");
        definition.put("usage", "二〇二六年十一月二十五日以后的归属记录。");
        assertThatThrownBy(() -> compiler.compile(basis, nextDay, true, "sales"))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("KNOWLEDGE_INSUFFICIENT");
    }

    /**
     * 明确或缺失日期窗口都不能覆盖仍未结构化的支付状态限制。
     */
    @ParameterizedTest
    @ValueSource(strings = {"NONE", "DATE_RANGE"})
    void refusesJoinsWithUnsupportedStatusConditions(String mode) {
        var relation = relation("83", "{\"pairs\":[{\"left\":\"14\",\"right\":\"31\"}]}");
        var content = (ObjectNode) relation.getContent();
        var window = content.putObject("relationWindow").put("mode", mode);
        if ("DATE_RANGE".equals(mode)) {
            // 计划日期在窗口内，应只因为支付条件无法执行而拒绝。
            window.put("validFrom", "2026-11-25");
        }
        var plan = orderRegionPlan(new Condition(new Field("orders", "business_day"), "2026-11-26"));
        for (var name : List.of("meaning", "rule", "usage")) {
            // 三处都可能保存治理文字中的额外条件。
            content.put(name, "仅适用于已支付订单");
            assertThatThrownBy(() -> new QuerySql().compile(orderBasis(relation), plan, true, "sales"))
                    .isInstanceOfSatisfying(QueryFailure.class, failure -> assertThat(failure.diagnostic())
                            .isEqualTo("RELATION_APPLICABILITY_UNVERIFIABLE"));
            content.remove(name);
        }
        content.put("rule", "orders.payment_state = 'PAID'");
        assertThatThrownBy(() -> new QuerySql().compile(orderBasis(relation), plan, true, "sales"))
                .isInstanceOfSatisfying(QueryFailure.class, failure -> assertThat(failure.diagnostic())
                        .isEqualTo("RELATION_APPLICABILITY_UNVERIFIABLE"));
        content.remove("rule");

        assertThat(new QuerySql()
                        .compile(orderBasis(relation), plan, true, "sales")
                        .sql())
                .contains(" JOIN ");
    }

    /**
     * 模拟规划器已识别订单金额和用户所述区域，下游自行解析所需关联。
     */
    private Plan orderRegionPlan(Condition... extra) {
        var filters = new ArrayList<>(List.of(new Condition(new Field("region", "name"), "华东区")));
        filters.addAll(List.of(extra));
        return new Plan("READY", null, "1", "SUM", "订单金额", null, List.of(new Field("orders", "amount")), filters);
    }

    /**
     * 用同一合成目录验证不同发布关联，所有字段采用确切目录编号。
     */
    private Basis orderBasis(GovernanceFactVO... relations) {
        return orderBasis("TABLE", relations);
    }

    /**
     * 保留复合主键和普通表类型，按需要把旁路对象替换为视图。
     */
    private Basis orderBasis(String warehouseType, GovernanceFactVO... relations) {
        var tables = Map.of(
                "orders",
                        orderTable(
                                1,
                                "orders",
                                "TABLE",
                                List.of(),
                                orderColumn(11, "outlet_id", "int"),
                                orderColumn(12, "tenant_id", "int"),
                                orderColumn(13, "amount", "decimal(12,2)"),
                                orderColumn(14, "region_id", "int"),
                                orderColumn(15, "outlet_label", "varchar(30)"),
                                orderColumn(16, "business_day", "date"),
                                orderColumn(17, "gross_amount", "decimal(12,2)"),
                                orderColumn(18, "payment_state", "varchar(12)"),
                                orderColumn(19, "currency_code", "varchar(3)")),
                "outlet",
                        orderTable(
                                2,
                                "outlet",
                                "TABLE",
                                List.of("id", "tenant_id"),
                                orderColumn(21, "id", "int"),
                                orderColumn(22, "tenant_id", "int"),
                                orderColumn(23, "region_id", "int"),
                                orderColumn(24, "previous_region_id", "int")),
                "region",
                        orderTable(
                                3,
                                "region",
                                "TABLE",
                                List.of("id"),
                                orderColumn(31, "id", "int"),
                                orderColumn(32, "name", "varchar(40)")),
                "warehouse",
                        orderTable(
                                4,
                                "warehouse",
                                warehouseType,
                                List.of(),
                                orderColumn(41, "id", "int"),
                                orderColumn(42, "region_id", "int")));
        var fields = tables.entrySet().stream()
                .flatMap(table -> table.getValue().entry().columns().stream()
                        .map(column ->
                                Map.entry(Long.toString(column.columnId()), new Field(table.getKey(), column.name()))))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        var labels = fields.values().stream().collect(Collectors.toMap(field -> field, Field::column));
        var metric = new GovernanceFactVO(
                new GovernanceTargetVO("1", "METRIC", "80"),
                "80",
                1,
                "10",
                "订单金额",
                JsonMapper.builder()
                        .build()
                        .readTree(
                                """
                        {"rule":"SUM(orders.amount)","meaning":"订单金额合计",
                         "metricRules":{"dateMode":"NONE","fixedFilters":[],"unitMode":"NONE"}}
                        """),
                List.of());
        var facts = Stream.concat(Stream.of(metric), Arrays.stream(relations))
                .collect(Collectors.toMap(fact -> fact.getTarget().getTargetId(), fact -> fact));
        return new Basis(null, null, 0, "订单区域统计", tables, fields, facts, labels);
    }

    /**
     * 使用与正式查询一致的关联事实格式和复合键结构。
     */
    private GovernanceFactVO relation(String id, String content) {
        return new GovernanceFactVO(
                new GovernanceTargetVO("1", "RELATION", id),
                id,
                1,
                "10",
                "已确认关联",
                JsonMapper.builder().build().readTree(content),
                List.of());
    }

    /**
     * 为每张测试表保留独立目录对象编号，用于核对实际血缘。
     */
    private Table orderTable(long id, String name, String type, List<String> primaryKey, Column... columns) {
        var ref = new ObjectRef("sales", "", name, type, "", null, null);
        var primary = primaryKey.stream()
                .map(field -> new Key("PRIMARY", field, 1, null, null, true))
                .toList();
        var structure = new Structure(ref, List.of(), primary, List.of(), List.of());
        var observation = new Observation(id, 1, 1L, 10L, null, null, null, structure, null);
        return new Table(new Entry(id, 1, false, observation, List.of(columns)), Map.of());
    }

    /**
     * 简化测试列的构造，字段与修订均用该列唯一编号。
     */
    private Column orderColumn(long id, String name, String type) {
        return new Column(id, id, name, type, false, "", (int) id);
    }

    /** 保留同一确切字段和指标，仅测试真实目录对象类型变化。 */
    private Basis basis(String type, Field stock, Field locked, Field date, GovernanceFactVO metric) {
        var ref = new ObjectRef("warehouse", "", "stock_snapshot", type, "", null, null);
        var structure = new Structure(ref, List.of(), List.of(), List.of(), List.of());
        var observation = new Observation(1, 1, 1L, 10L, null, null, null, structure, null);
        var catalog = new Entry(
                1,
                1,
                false,
                observation,
                List.of(
                        new Column(6, 7, "business_date", "date", false, "", 3),
                        new Column(8, 9, "on_hand", "decimal(9,2)", false, "", 4),
                        new Column(10, 11, locked.column(), "decimal(9,2)", false, "", 5)));
        var available = Map.of("stock_snapshot", new Table(catalog, Map.of()));
        var labels = Map.of(stock, "实际在库", locked, "锁定量", date, "业务日");
        return new Basis(
                null,
                null,
                0,
                "合成证据示例为 12 箱。",
                available,
                Map.of("6", date),
                metric == null ? Map.of() : Map.of("8", metric),
                labels);
    }
}
