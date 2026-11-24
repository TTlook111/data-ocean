package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dataocean.module.query.service.QueryProtocol.Condition;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.HistorySummary;
import com.dataocean.module.query.service.QueryProtocol.Memory;
import com.dataocean.module.query.service.QueryProtocol.MemoryTurn;
import com.dataocean.module.query.service.QueryProtocol.Plan;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 摘要保留最新适用条件并拒绝不在原证据中的模型引用。
 */
class QueryHistorySummaryTest {

    /** 两个规格均移出窗口时，只有更正后的实际计划参与摘要。 */
    @Test
    void compactsSupersededSpecificationWithoutSendingHistoricalText() {
        var initial = turn("older", "日末可售库存", "SKU-1");
        var correction = turn("corrected", "日末可售库存", "SKU-2");
        var otherMetric = turn("recent", "在途库存", "SKU-3");
        var memory = new Memory("anchor", List.of(otherMetric), List.of(initial, correction));

        var input = QueryHistorySummary.input(memory);

        assertThat(input.earlier()).extracting(MemoryTurn::requestId).containsExactly("corrected");
        assertThat(input.earlier().get(0).plan().filters().get(0).value()).isEqualTo("SKU-2");
        assertThat(input.earlier().get(0).supplement()).isNull();
        assertThat(input.recent().get(0).question()).isNull();
        var decision = QueryHistorySummary.decide(input, new HistorySummary(List.of("corrected")));
        assertThat(decision.applied()).isTrue();
        assertThat(QueryHistorySummary.apply(memory, decision).recent()).containsExactly(otherMetric);
    }

    /** 新近纠正可以排除旧值，同列在另一指标的不同值不会把历史混到一起。 */
    @Test
    void keepsOtherMetricsAndFallsBackForInvalidReferences() {
        var older = turn("older", "日末可售库存", "SKU-1");
        var otherMetric = turn("alsoOlder", "在途库存", "SKU-3");
        var recent = turn("recent", "日末可售库存", "SKU-2");
        var memory = new Memory("anchor", List.of(recent), List.of(older, otherMetric));
        var input = QueryHistorySummary.input(memory);
        assertThat(input.earlier()).extracting(MemoryTurn::requestId).containsExactly("alsoOlder");

        for (var bad : List.of(List.of("older"), List.of("recent"), List.of("alsoOlder", "alsoOlder"))) {
            // 失效、近期或重复引用都不交给后续节点。
            var decision = QueryHistorySummary.decide(input, new HistorySummary(bad));
            assertThat(decision.applied()).isFalse();
            assertThat(decision.requestIds()).containsExactly("alsoOlder");
        }
    }

    /** 模拟含商品编码和原话的已核验结构化计划。 */
    private MemoryTurn turn(String id, String metric, String code) {
        var sku = new Field("sku", "code");
        return new MemoryTurn(id, "原问题", "原补充", new Plan("READY", null, "1", "SUM", metric,
                "箱", List.of(new Field("stock", "qty")), List.of(new Condition(sku, code))));
    }
}
