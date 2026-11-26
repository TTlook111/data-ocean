package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.dataocean.module.query.service.QueryCorpus.Basis;
import com.dataocean.module.query.service.QueryProtocol.Hit;
import com.dataocean.module.query.service.QueryProtocol.RerankDecision;
import com.dataocean.module.query.service.QueryProtocol.RerankOrder;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 候选排序不能创造、替换或丢弃正式知识片段。 */
class QueryRerankingTest {

    /** 使用两个有效同源片段的固定身份。 */
    private final List<Hit> hits = List.of(new Hit("1", 0, "a"), new Hit("2", 0, "b"));

    /** 重复、遗漏和越界索引都回退到原召回顺序。 */
    @Test
    void preservesRecallForMalformedOrder() {
        for (var order : List.of(List.of(0, 0), List.of(0), List.of(1, 2))) {
            // 无效结果不应成为后续规划依据。
            assertThat(QueryReranking.decide(hits, new RerankOrder(order)))
                    .isEqualTo(new RerankDecision(hits, false));
        }
        assertThat(QueryReranking.decide(hits, null)).isEqualTo(new RerankDecision(hits, false));
    }

    /** 只对同一批身份应用已保存顺序，同编号不同摘要要拒绝。 */
    @Test
    void restoresOnlyTheSameVerifiedFragments() {
        var before = List.of(mock(Basis.class), mock(Basis.class));
        var result = QueryReranking.decide(hits, new RerankOrder(List.of(1, 0)));
        assertThat(result.applied()).isTrue();
        assertThat(QueryReranking.restore(hits, before, result)).containsExactly(before.get(1), before.get(0));
        assertThatThrownBy(() -> QueryReranking.restore(List.of(hits.get(0), new Hit("2", 0, "changed")), before, result))
                .isInstanceOf(QueryFailure.class);
    }
}
