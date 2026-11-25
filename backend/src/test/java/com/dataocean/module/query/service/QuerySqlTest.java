package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataocean.module.datasource.mapper.CatalogMapper.Observation;
import com.dataocean.module.datasource.mapper.GovernanceCatalogMapper.Column;
import com.dataocean.module.datasource.service.GovernanceCatalog.Entry;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;
import com.dataocean.module.datasource.source.SourceMetadata.Structure;
import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
import com.dataocean.module.governance.entity.vo.GovernanceTargetVO;
import com.dataocean.module.query.service.QueryCorpus.Basis;
import com.dataocean.module.query.service.QueryCorpus.Table;
import com.dataocean.module.query.service.QueryProtocol.Condition;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.Memory;
import com.dataocean.module.query.service.QueryProtocol.MemoryTurn;
import com.dataocean.module.query.service.QueryProtocol.Plan;
import com.dataocean.module.query.service.QueryProtocol.TimeSelection;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

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
                 "meaning":"实际在库扣除锁定。","usage":"不计在途。"}
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
                 "meaning":"实际在库扣除锁定。","usage":"按业务日期统计。"}
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
        var previous = new Plan("READY", null, "1", "SUM", "库存", null, List.of(stock),
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
        var available = new GovernanceFactVO(new GovernanceTargetVO("1", "METRIC", "8"), "9", 1, "10",
                "可售库存", json.readTree("{\"rule\":\"SUM(stock_snapshot.on_hand - stock_snapshot.locked)\"}"),
                List.of());
        var onHand = new GovernanceFactVO(new GovernanceTargetVO("1", "METRIC", "9"), "10", 1, "10",
                "实际在库", json.readTree("{\"rule\":\"SUM(stock_snapshot.on_hand)\"}"), List.of());
        var section = basis("TABLE", stock, locked, date, available);
        var evidence = new Basis(section.source(), section.binding(), section.ordinal(), section.text(),
                section.tables(), section.fields(), Map.of("8", available, "9", onHand), section.labels());
        var compiler = new QuerySql();
        var mismatch = new Plan("READY", null, "1", "SUM", "可售库存", null, List.of(stock), List.of());
        var alias = new Plan("READY", null, "1", "SUM", "实际在库总量", null, List.of(stock), List.of());

        assertThatThrownBy(() -> compiler.compile(evidence, mismatch, true, "warehouse"))
                .isInstanceOf(QueryFailure.class);
        assertThat(compiler.compile(evidence, alias, true, "warehouse").columns().get(0).getLabel())
                .isEqualTo("实际在库");
    }

    /** 日期解析与商品校验对「上周」使用同一组纯追问表达。 */
    @ParameterizedTest
    @ValueSource(strings = {"上周？", "那么上个星期？", "那上周呢？"})
    void acceptsConfirmedProductForAllPreviousWeekFollowups(String question) {
        var code = new Field("product", "code");
        var evidence = new Basis(null, null, 0, "", Map.of(), Map.of(), Map.of(), Map.of(code, "SKU 编码"));
        var plan = new Plan("READY", null, "1", "SUM", "库存", null,
                List.of(code), List.of(new Condition(code, "DEMO-2")));
        var memory = new Memory("2026-11-24T00:00:00Z",
                List.of(new MemoryTurn("confirmed", "查 DEMO-2 的库存", null, plan)), List.of());

        assertThat(QueryDates.previousWeekFollowup(question)).isTrue();
        assertThat(new QuerySql().checkProductCodes(evidence, plan, question, memory).contextRequestIds())
                .containsExactly("confirmed");
        assertThatThrownBy(() -> new QuerySql().checkProductCodes(evidence, plan,
                question + "另查其他商品。", memory)).isInstanceOf(QueryFailure.class);
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
                 "meaning":"统计有快照的唯一键。","usage":"跨日或跨仓出现的同一商品只计一次。"}
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
