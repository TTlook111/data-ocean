package com.dataocean.module.fieldsemantic.service.impl;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class BusinessKnowledgePolicyTest {
    @Test void unqualifiedOrNestedExpressionsCannotHideDependencies(){
        for(String formula:List.of("SUM(amount)","(SELECT SUM(secret.amount) FROM secret)","SUM(o.amount)","SUM(orders.amount ORDER BY orders.secret)","ROW_NUMBER() OVER(ORDER BY orders.secret)"))
            assertThatThrownBy(()->BusinessKnowledgePolicy.requireDependencies("METRIC",Map.of("formula",formula),List.of("table:orders","column:orders.id")))
                    .isInstanceOf(com.dataocean.common.exception.BusinessException.class);
    }
    @Test void supportedExpressionsBindEveryColumnAndIgnoreLiteralText(){
        BusinessKnowledgePolicy.requireDependencies("METRIC",Map.of("formula","SUM(CASE WHEN orders.state = 'US.EAST' THEN orders.amount ELSE 0 END) / 100","conditions","无"),
                List.of("table:orders","column:orders.state","column:orders.amount"));
    }
    @Test void quotedLiteralDotsAreNotResourceDependencies(){
        BusinessKnowledgePolicy.requireDependencies("METRIC",Map.of("formula","SUM(orders.amount)","conditions","orders.region = 'US.EAST'"),List.of("table:orders","column:orders.amount","column:orders.region"));
    }
    @Test void formulaCannotHideReferencedFieldsOutsideFullDependencySet(){
        assertThatThrownBy(()->BusinessKnowledgePolicy.requireDependencies("METRIC",Map.of("formula","SUM(orders.hidden_amount)"),List.of("table:orders","column:orders.amount")))
                .hasMessageContaining("完整依赖");
    }
    @Test void tableKnowledgeNeedsGrainAndUnresolvedKnowledgeCannotPassReview() {
        assertThatThrownBy(() -> BusinessKnowledgePolicy.requireReady("TABLE", Map.of("description", "订单表"), List.of()))
                .hasMessageContaining("表粒度");
        assertThatThrownBy(() -> BusinessKnowledgePolicy.requireReady("TABLE", Map.of("description", "订单", "grain", "一行一个订单"), List.of("单位未知")))
                .hasMessageContaining("待确认");
    }
    @Test void relationNeedsCardinalityAndAggregationCaveats() {
        assertThatThrownBy(() -> BusinessKnowledgePolicy.requireReady("RELATION", Map.of("description", "订单明细", "joinCondition", "a.id=b.id"), List.of()))
                .hasMessageContaining("关系基数");
        BusinessKnowledgePolicy.requireReady("RELATION", Map.of("description", "订单明细", "joinCondition", "a.id=b.id",
                "cardinality", "1:N", "aggregationNotes", "订单金额先聚合避免重复"), List.of());
    }
}
