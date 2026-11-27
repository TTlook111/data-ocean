package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dataocean.module.datasource.mapper.GovernanceCatalogMapper.Column;
import com.dataocean.module.datasource.service.GovernanceCatalog.Entry;
import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
import com.dataocean.module.governance.entity.vo.GovernanceTargetVO;
import com.dataocean.module.query.service.QueryCorpus.Basis;
import com.dataocean.module.query.service.QueryCorpus.Table;
import com.dataocean.module.query.service.QueryProtocol.Condition;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.Plan;
import com.dataocean.module.query.service.QueryProtocol.RequestedTerm;
import com.dataocean.module.query.service.QuerySql.ValueComparison;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * 用户范围应用实际字段上的追加、替换和撤销语义。
 */
class QueryRequestedScopeTest {

    /**
     * 不同补充动作都指向同一列。
     */
    private static final Field PAY = new Field("orders", "payment_state");

    /**
     * 支付状态原值不依赖模型给出的默认填充。
     */
    private static final Plan PLAN = new Plan("READY", null, "1", "VALUES", null, null, List.of(PAY), List.of());

    /**
     * 不同排除项可以保留，替换和撤销只在用户明确授权的维度执行。
     */
    @Test
    void appliesExplicitEditsInSourceOrder() {
        var basis = orderStatus();
        var query = "只统计已支付";
        var paid = new RequestedTerm(query, "已支付", null, "INCLUDE");
        var add = "再排除已退款";
        var exclude = new RequestedTerm(add, "已退款", null, "EXCLUDE", "SUPPLEMENT", "APPEND");
        var replace = "改成未支付";
        var unpaid = new RequestedTerm(replace, "未支付", null, "INCLUDE", "SUPPLEMENT", "REPLACE");
        var remove = "撤销排除已退款";
        var undo = new RequestedTerm(remove, "已退款", null, "EXCLUDE", "SUPPLEMENT", "REMOVE");
        var clear = "支付状态不限";
        var clearRange = new RequestedTerm(clear, null, "支付状态", null, "SUPPLEMENT", "REMOVE");
        ValueComparison comparison =
                (source, field, first, second) -> first.toString().equalsIgnoreCase(second.toString());

        // 追加保留已支付限定，移除单项保留其余范围。
        assertThat(QueryRequestedScope.resolve(List.of(basis), PLAN, List.of(paid, exclude), query, add, comparison)
                        .conditions())
                .containsExactly(new Condition(PAY, "PAID"), new Condition(PAY, "REFUNDED", true));
        assertThat(QueryRequestedScope.resolve(
                                List.of(basis),
                                PLAN,
                                List.of(paid, undo, exclude),
                                query,
                                add + "。" + remove,
                                comparison)
                        .conditions())
                .containsExactly(new Condition(PAY, "PAID"));
        assertThat(QueryRequestedScope.resolve(List.of(basis), PLAN, List.of(paid, unpaid), query, replace, comparison)
                        .conditions())
                .containsExactly(new Condition(PAY, "UNPAID"));

        // 撤销整列用户条件时，规划复述的旧值及历史计划同列旧值都不会复活。
        var cleared =
                QueryRequestedScope.resolve(List.of(basis), PLAN, List.of(paid, clearRange), query, clear, comparison);
        var compiler = new QuerySql();
        var previous = compiler.withTime(PLAN, List.of(new Condition(PAY, "PAID")), null);
        assertThat(cleared.comparisonPlan(compiler, previous).filters()).isEmpty();
        assertThat(cleared.apply(compiler, basis, previous).filters()).isEmpty();
    }

    /**
     * 编码映射等价依据数据库比较，同一证据片段保留跨行否定上下文。
     */
    @Test
    void bindsEqualCodesAndRejectsDetachedNegation() {
        var basis = orderStatus();
        var phrase = "不要统计下列状态：\n已支付，已退款";
        var term = new RequestedTerm(phrase, "已支付", null, "EXCLUDE");
        ValueComparison insensitive =
                (source, field, first, second) -> first.toString().equalsIgnoreCase(second.toString());
        ValueComparison exact = (source, field, first, second) -> Objects.equals(first, second);

        // 已确认的两个等价编码在绑定阶段只保留一个，源库实际区分时继续澄清。
        assertThat(QueryRequestedScope.resolve(List.of(basis), PLAN, List.of(term), phrase, null, insensitive)
                        .conditions())
                .containsExactly(new Condition(PAY, "PAID", true));
        assertThat(QueryRequestedScope.resolve(List.of(basis), PLAN, List.of(term), phrase, null, exact))
                .isNull();
        assertThat(QueryRequestedScope.resolve(
                        List.of(basis),
                        PLAN,
                        List.of(new RequestedTerm("已支付", "已支付", null, "INCLUDE")),
                        phrase,
                        null,
                        insensitive))
                .isNull();
    }

    /**
     * 同一句中的不同编辑和重复出现的原话按真实位置执行。
     */
    @Test
    void ordersMixedEditsByTheirVerifiedOccurrences() {
        var basis = orderStatus();
        var question = "只统计已支付";
        var paid = new RequestedTerm(question, "已支付", null, "INCLUDE");
        var change = "改成只统计未支付";
        var clear = "撤销支付状态限制";
        var supplement = change + "。" + clear + "。" + change;
        var add = "再排除已退款";
        var append = new RequestedTerm(add, "已退款", null, "EXCLUDE", "SUPPLEMENT", "APPEND", supplement + "。" + add);
        var reset = new RequestedTerm(clear, null, "支付状态", null, "SUPPLEMENT", "REMOVE", change + "。撤销支付状态");
        var first = new RequestedTerm(change, "未支付", null, "INCLUDE", "SUPPLEMENT", "REPLACE", change);
        var last = new RequestedTerm(change, "未支付", null, "INCLUDE", "SUPPLEMENT", "REPLACE", supplement);
        ValueComparison exact = (source, field, left, right) -> left.equals(right);

        // 末尾替换和首次替换的完整句子相同，模型返回顺序也被打乱。
        assertThat(QueryRequestedScope.resolve(
                                List.of(basis),
                                PLAN,
                                List.of(last, append, paid, reset, first),
                                question,
                                supplement + "。" + add,
                                exact)
                        .conditions())
                .containsExactly(new Condition(PAY, "UNPAID"), new Condition(PAY, "REFUNDED", true));

        // 追加与替换也可同在一个包含逗号的完整句子内。
        var finalClause = change + "，" + add;
        var finalText = supplement + "，" + add;
        var combinedReplace =
                new RequestedTerm(finalClause, "未支付", null, "INCLUDE", "SUPPLEMENT", "REPLACE", supplement);
        var combinedAppend = new RequestedTerm(finalClause, "已退款", null, "EXCLUDE", "SUPPLEMENT", "APPEND", finalText);
        assertThat(QueryRequestedScope.resolve(
                                List.of(basis),
                                PLAN,
                                List.of(combinedAppend, combinedReplace, paid, reset, first),
                                question,
                                finalText,
                                exact)
                        .conditions())
                .containsExactly(new Condition(PAY, "UNPAID"), new Condition(PAY, "REFUNDED", true));
    }

    /**
     * 已经作废的旧值可以有多个编码，只有继续生效时才澄清。
     */
    @Test
    void validatesOnlyValuesThatSurviveEditing() {
        var basis = orderStatus();
        var question = "只统计已支付";
        var paid = new RequestedTerm(question, "已支付", null, "INCLUDE");
        var replace = new RequestedTerm("改成未支付", "未支付", null, "INCLUDE", "SUPPLEMENT", "REPLACE");
        var clear = new RequestedTerm("撤销支付状态限制", null, "支付状态", null, "SUPPLEMENT", "REMOVE");
        var undo = new RequestedTerm("撤销只统计已支付", "已支付", null, "INCLUDE", "SUPPLEMENT", "REMOVE");
        ValueComparison exact = (source, field, left, right) -> left.equals(right);

        assertThat(QueryRequestedScope.resolve(List.of(basis), PLAN, List.of(paid), question, null, exact))
                .isNull();
        assertThat(QueryRequestedScope.resolve(
                                List.of(basis), PLAN, List.of(paid, replace), question, replace.phrase(), exact)
                        .conditions())
                .containsExactly(new Condition(PAY, "UNPAID"));
        assertThat(QueryRequestedScope.resolve(
                                List.of(basis), PLAN, List.of(paid, clear), question, clear.phrase(), exact)
                        .conditions())
                .isEmpty();
        assertThat(QueryRequestedScope.resolve(
                                List.of(basis), PLAN, List.of(paid, undo), question, undo.phrase(), exact)
                        .conditions())
                .isEmpty();
    }

    /**
     * 只确认大写编码时，原话的小写值仍以源列规则绑定及撤销。
     */
    @Test
    void bindsPublishedCodeBySourceEqualityWithoutModelFilter() {
        var basis = orderStatus();
        ((ArrayNode) basis.fact("COLUMN", 15).getContent().path("valueMappings")).remove(1);
        var question = "只统计 paid";
        var paid = new RequestedTerm(question, "paid", null, "INCLUDE");
        var remove = new RequestedTerm("撤销只统计 PAID", "PAID", null, "INCLUDE", "SUPPLEMENT", "REMOVE");
        ValueComparison insensitive =
                (source, field, left, right) -> left.toString().equalsIgnoreCase(right.toString());

        assertThat(QueryRequestedScope.resolve(List.of(basis), PLAN, List.of(paid), question, null, insensitive)
                        .conditions())
                .containsExactly(new Condition(PAY, "PAID"));
        assertThat(QueryRequestedScope.resolve(
                                List.of(basis), PLAN, List.of(paid, remove), question, remove.phrase(), insensitive)
                        .conditions())
                .isEmpty();
        assertThat(QueryRequestedScope.resolve(
                        List.of(basis),
                        PLAN,
                        List.of(paid),
                        question,
                        null,
                        (source, field, left, right) -> left.equals(right)))
                .isNull();
    }

    /**
     * 多个条件共用完整原句时，各自只绑定自己引用的值。
     */
    @Test
    void sharedSentenceDoesNotMixProductAndPaymentBindings() {
        var basis = orderStatus();
        var sku = new Field("orders", "sku_code");
        var entry = new Entry(
                1,
                1,
                false,
                null,
                List.of(
                        new Column(15, 15, PAY.column(), "varchar", false, "", 1),
                        new Column(16, 16, sku.column(), "varchar", false, "", 2)));
        when(basis.tables())
                .thenReturn(Map.of("orders", new Table(entry, Map.of(PAY.column(), "支付状态", sku.column(), "SKU 编码"))));
        when(basis.labels()).thenReturn(Map.of(PAY, "支付状态", sku, "SKU 编码"));
        when(basis.text()).thenReturn("CODE-900 是 1 L 原味燕麦奶。");
        var question = "查1升原味燕麦奶，只统计已支付";
        var terms = List.of(
                new RequestedTerm(question, "1升原味燕麦奶", null, "INCLUDE"),
                new RequestedTerm(question, "已支付", null, "INCLUDE"));
        ValueComparison comparison =
                (source, field, first, second) -> first.toString().equalsIgnoreCase(second.toString());

        assertThat(QueryRequestedScope.resolve(List.of(basis), PLAN, terms, question, null, comparison)
                        .conditions())
                .containsExactly(new Condition(sku, "CODE-900"), new Condition(PAY, "PAID"));
    }

    /**
     * 原话使用确认过的业务维度名称时，可以识别映射值后的冗余维度词。
     */
    @Test
    void resolvesAConfirmedBusinessDimensionAndItsMappedValue() {
        var basis = orderStatus();
        var fact = basis.fact("COLUMN", 15L);
        fact.setContent(
                JsonMapper.builder()
                        .build()
                        .readTree(
                                """
                {"businessName":"订单状态","valueMappings":[{"code":"PAID","meaning":"已支付"}]}
                """));
        var question = "查已支付订单状态的金额";
        var terms = List.of(new RequestedTerm(question, "已支付订单状态", "订单状态", "INCLUDE"));
        ValueComparison comparison = (source, field, first, second) -> first.equals(second);
        assertThat(QueryRequestedScope.resolve(List.of(basis), PLAN, terms, question, null, comparison)
                        .conditions())
                .containsExactly(new Condition(PAY, "PAID"));
    }

    /**
     * 使用正式字段映射形状提供明确物理身份和数据库原值。
     */
    private Basis orderStatus() {
        var basis = mock(Basis.class);
        var entry = new Entry(1, 1, false, null, List.of(new Column(15, 15, PAY.column(), "varchar", false, "", 1)));
        var column = new GovernanceFactVO(
                new GovernanceTargetVO("1", "COLUMN", "15"),
                "15",
                1,
                "1",
                "支付状态",
                JsonMapper.builder()
                        .build()
                        .readTree(
                                """
                        {"valueMappings":[{"code":"PAID","meaning":"已支付"},{"code":"paid","meaning":"已支付"},
                          {"code":"UNPAID","meaning":"未支付"},{"code":"REFUNDED","meaning":"已退款"}]}
                        """),
                List.of());
        when(basis.tables()).thenReturn(Map.of("orders", new Table(entry, Map.of(PAY.column(), "支付状态"))));
        when(basis.labels()).thenReturn(Map.of(PAY, "支付状态"));
        when(basis.fact("COLUMN", 15)).thenReturn(column);
        return basis;
    }
}
