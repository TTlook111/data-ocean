package com.dataocean.module.knowledge.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.datasource.service.GovernanceSourceRows;
import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
import com.dataocean.module.governance.service.GovernanceKnowledgeService;
import com.dataocean.module.knowledge.entity.dto.KnowledgeTestDraftDTO.CountRule;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper.Reference;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

/**
 * 核对发布计数题在审核与执行入口共用关联适用性校验。
 */
class KnowledgeValidationQueryTest {

    /**
     * 日期区间和额外业务筛选都不能被单个分类值替代。
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                """
        {"defaultMetric":false,"pairs":[{"left":"5","right":"6"}],"relationWindow":{"mode":"DATE_RANGE","validFrom":"2026-11-25"}}
        """,
                """
        {"defaultMetric":false,"pairs":[{"left":"5","right":"6"}],"usage":"仅适用于已支付订单","relationWindow":{"mode":"NONE"}}
        """
            })
    void rejectsLimitedRelationsBeforeReviewOrExecution(String content) {
        var source = mock(Source.class);
        var catalog = mock(GovernanceCatalog.class);
        var rows = mock(GovernanceSourceRows.class);
        var governance = mock(GovernanceKnowledgeService.class);
        var mapper = JsonMapper.builder().build();
        when(source.id()).thenReturn(1L);
        var metric =
                new GovernanceFactVO(null, "8", 1, "9", "商品数", mapper.readTree("{\"defaultMetric\":false}"), List.of());
        var relation = new GovernanceFactVO(null, "8", 1, "9", "商品关联", mapper.readTree(content), List.of());
        when(governance.require(eq(source), any())).thenReturn(metric, relation, metric, relation);

        var evidence = List.of(
                new Reference(0, 1, 2, 1, "COLUMN", 4, 8, 9, true, "商品分类"),
                new Reference(0, 1, 2, 1, "METRIC", 10, 8, 9, true, "商品数"),
                new Reference(0, 1, 2, 1, "RELATION", 11, 8, 9, true, "商品关联"));
        var rule = new CountRule();
        rule.setCategoryId("4");
        rule.setMetricId("10");
        rule.setRelationId("11");
        rule.setCategoryValue("饮料");
        var service = new KnowledgeValidationQuery(catalog, governance, rows, new JsonCodec(mapper));

        assertThatThrownBy(() -> service.verify(source, evidence, rule))
                .isInstanceOf(BizIllegalException.class)
                .hasMessageContaining("不限日期的关联");
        assertThatThrownBy(() -> service.execute(source, evidence, rule))
                .isInstanceOf(BizIllegalException.class)
                .hasMessageContaining("不限日期的关联");
        verifyNoInteractions(catalog, rows);
    }
}
