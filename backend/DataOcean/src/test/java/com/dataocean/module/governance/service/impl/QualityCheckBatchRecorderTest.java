package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import com.dataocean.module.governance.entity.po.QualityCheckBatch;
import com.dataocean.module.governance.entity.po.QualityCheckBatchRule;
import com.dataocean.module.governance.mapper.QualityCheckBatchMapper;
import com.dataocean.module.governance.mapper.QualityCheckBatchRuleMapper;
import com.dataocean.module.governance.mapper.QualityCheckResultMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QualityCheckBatchRecorderTest {
    @BeforeAll
    static void initTableInfo() {
        for (Class<?> entity : List.of(QualityCheckBatch.class, QualityCheckBatchRule.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
    }

    @Test
    void batchRuleSnapshotDoesNotChangeWhenCurrentRuleIsEditedOrDisabled() {
        QualityCheckBatchMapper batchMapper = mock(QualityCheckBatchMapper.class);
        QualityCheckBatchRuleMapper ruleMapper = mock(QualityCheckBatchRuleMapper.class);
        QualityCheckBatch persistedBatch = new QualityCheckBatch();
        persistedBatch.setId(50L);
        when(batchMapper.insert(any(QualityCheckBatch.class))).thenAnswer(invocation -> {
            QualityCheckBatch batch = invocation.getArgument(0);
            batch.setId(50L);
            return 1;
        });
        when(batchMapper.selectById(anyLong())).thenReturn(persistedBatch);
        QualityCheckBatchRecorder recorder = new QualityCheckBatchRecorder(
                batchMapper, ruleMapper, mock(QualityCheckResultMapper.class), new ObjectMapper());

        MetadataQualityRule liveRule = new MetadataQualityRule();
        liveRule.setId(12L);
        liveRule.setRuleCode("QR-INSTANCE");
        liveRule.setTemplateCode("DATA_NULL_RATE_HIGH");
        liveRule.setRuleName("空值率");
        liveRule.setDimension(MetadataQualityRule.DIM_COMPLETENESS);
        liveRule.setSeverity("HIGH");
        liveRule.setDeductionPoints(new BigDecimal("3.0"));
        liveRule.setScopeType("COLUMN");
        liveRule.setDatasourceId(5L);
        liveRule.setTargetTableName("orders");
        liveRule.setTargetColumnName("amount");
        liveRule.setParametersJson("{\"threshold\":0.25}");
        liveRule.setConfigVersion(4);
        liveRule.setEnabled(1);

        Long batchId = recorder.begin(5L, 90L, "TABLE", List.of(MetadataQualityRule.DIM_COMPLETENESS),
                List.of("orders"), 7L);
        recorder.captureRules(batchId, List.of(liveRule));
        org.mockito.ArgumentCaptor<QualityCheckBatchRule> snapshotCaptor =
                org.mockito.ArgumentCaptor.forClass(QualityCheckBatchRule.class);
        org.mockito.Mockito.verify(ruleMapper).insert(snapshotCaptor.capture());

        liveRule.setRuleName("新名称");
        liveRule.setConfigVersion(5);
        liveRule.setParametersJson("{\"threshold\":0.75}");
        liveRule.setEnabled(0);

        QualityCheckBatchRule frozen = snapshotCaptor.getValue();
        assertThat(frozen.getRuleName()).isEqualTo("空值率");
        assertThat(frozen.getRuleVersion()).isEqualTo(4);
        assertThat(frozen.getParametersJson()).isEqualTo("{\"threshold\":0.25}");
        assertThat(frozen.getStatus()).isEqualTo("NOT_EXECUTED");
    }
}
