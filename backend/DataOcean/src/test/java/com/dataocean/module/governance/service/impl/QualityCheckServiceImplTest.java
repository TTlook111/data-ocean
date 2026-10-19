package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.checker.CompletenessChecker;
import com.dataocean.module.governance.checker.QualityChecker;
import com.dataocean.module.governance.entity.po.MetadataQualityIssue;
import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import com.dataocean.module.governance.entity.po.QualityCheckResult;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.governance.mapper.QualityCheckResultMapper;
import com.dataocean.module.governance.service.QualityCheckBatchService;
import com.dataocean.module.governance.service.QualityCheckService;
import com.dataocean.module.governance.service.QualityRuleService;
import com.dataocean.module.governance.service.QualityRuleTemplateRegistry;
import com.dataocean.module.governance.entity.vo.QualityCheckResultVO;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.entity.po.TableRelation;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityCheckServiceImplTest {
    @BeforeAll
    static void initTableInfo() {
        for (Class<?> entity : List.of(MetadataQualityIssue.class, QualityCheckResult.class,
                MetadataQualityRule.class, MetadataSnapshot.class, DbTableMeta.class,
                DbColumnMeta.class, TableRelation.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
    }

    @Test
    void checkerFailureIsRecordedAndCannotBecomeACompleteScore() {
        Fixture fixture = new Fixture();
        fixture.snapshot.setId(10L);
        fixture.snapshot.setDatasourceId(1L);
        fixture.tables(List.of(table(1L, "orders")));
        MetadataQualityRule rule = rule(1L, "COMP_TABLE_COMMENT_MISSING", "GLOBAL", null, null);
        fixture.rules(List.of(rule));
        QualityChecker checker = mock(QualityChecker.class);
        when(checker.getDimension()).thenReturn(MetadataQualityRule.DIM_COMPLETENESS);
        when(checker.check(any())).thenThrow(new BusinessException(400, "数据源未启用，无法执行数据质量检查"));
        fixture.checkers = List.of(checker);

        QualityCheckResultVO result = fixture.service().executeQualityCheck(10L, List.of(MetadataQualityRule.DIM_COMPLETENESS), null);

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getComplete()).isFalse();
        assertThat(result.getQualityScore()).isNull();
        assertThat(result.getErrorSummary()).contains("数据源未启用");
        verify(fixture.issueMapper, never()).insert(any(MetadataQualityIssue.class));
        verify(fixture.issueMapper, never()).delete(any());
        verify(fixture.checkResultMapper, never()).insert(any(QualityCheckResult.class));
        verify(fixture.snapshotMapper, never()).updateById(any(MetadataSnapshot.class));
        verify(fixture.batchService).finish(eq(10L), eq("FAILED"), eq(false), isNull(), any(), any());
    }

    @Test
    void twoInstancesOfOneTemplateUseSpecificityWithoutDoubleApplyingTheGlobalRule() {
        Fixture fixture = new Fixture();
        fixture.snapshot.setId(11L);
        fixture.snapshot.setDatasourceId(1L);
        fixture.tables(List.of(table(1L, "orders"), table(2L, "customers")));
        MetadataQualityRule global = rule(101L, "COMP_TABLE_COMMENT_MISSING", "GLOBAL", null, null);
        global.setRuleName("全局表说明");
        global.setDeductionPoints(new BigDecimal("5.0"));
        MetadataQualityRule local = rule(102L, "COMP_TABLE_COMMENT_MISSING", "TABLE", "orders", null);
        local.setRuleCode("QR-LOCAL");
        local.setRuleName("订单表专属说明");
        local.setDeductionPoints(new BigDecimal("8.0"));
        local.setConfigVersion(2);
        fixture.rules(List.of(global, local));
        fixture.checkers = List.of(new CompletenessChecker());

        QualityCheckResultVO result = fixture.service().executeQualityCheck(
                11L, List.of(MetadataQualityRule.DIM_COMPLETENESS), null);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getComplete()).isTrue();
        assertThat(result.getTotalIssues()).isEqualTo(2);
        assertThat(result.getDimensionScores().get(MetadataQualityRule.DIM_COMPLETENESS))
                .isEqualByComparingTo("87.0");
        verify(fixture.issueMapper, org.mockito.Mockito.times(2)).insert(any(MetadataQualityIssue.class));
        verify(fixture.batchService).finish(eq(10L), eq("SUCCESS"), eq(true), any(BigDecimal.class), eq(null), any());
    }

    @Test
    void emptyRuleSetIsFailedCoverageAndDoesNotApproveSnapshot() {
        Fixture fixture = new Fixture();
        fixture.snapshot.setId(12L);
        fixture.snapshot.setDatasourceId(1L);
        fixture.rules(List.of());
        fixture.checkers = List.of(new CompletenessChecker());

        QualityCheckResultVO result = fixture.service().executeQualityCheck(12L, null, null);

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getComplete()).isFalse();
        assertThat(result.getQualityScore()).isNull();
        assertThat(result.getErrorSummary()).contains("没有启用且适用");
        verify(fixture.snapshotMapper, never()).updateById(any(MetadataSnapshot.class));
        verify(fixture.checkResultMapper, never()).insert(any(QualityCheckResult.class));
    }

    @Test
    void postPublishFailureNeverChangesPublishedStatus() {
        Fixture fixture = new Fixture();
        fixture.snapshot.setId(13L);
        fixture.snapshot.setDatasourceId(1L);
        fixture.snapshot.setStatus(MetadataSnapshot.STATUS_PUBLISHED);
        fixture.rules(List.of(rule(103L, "COMP_TABLE_COMMENT_MISSING", "GLOBAL", null, null)));
        fixture.tables(List.of(table(3L, "orders")));
        QualityChecker checker = mock(QualityChecker.class);
        when(checker.getDimension()).thenReturn(MetadataQualityRule.DIM_COMPLETENESS);
        when(checker.check(any())).thenThrow(new IllegalStateException("test failure"));
        fixture.checkers = List.of(checker);

        fixture.service().executeQualityCheck(13L, List.of(MetadataQualityRule.DIM_COMPLETENESS), null);

        assertThat(fixture.snapshot.getStatus()).isEqualTo(MetadataSnapshot.STATUS_PUBLISHED);
        verify(fixture.snapshotMapper, never()).updateById(any(MetadataSnapshot.class));
    }

    private static MetadataQualityRule rule(Long id, String template, String scope, String table, String column) {
        MetadataQualityRule rule = new MetadataQualityRule();
        rule.setId(id);
        rule.setRuleCode("QR-" + id);
        rule.setTemplateCode(template);
        rule.setRuleName(template);
        rule.setDimension(MetadataQualityRule.DIM_COMPLETENESS);
        rule.setCheckTarget(MetadataQualityRule.TARGET_TABLE);
        rule.setCheckType(MetadataQualityRule.CHECK_TYPE_SCHEMA);
        rule.setScopeType(scope);
        rule.setDatasourceId("GLOBAL".equals(scope) ? null : 1L);
        rule.setTargetTableName(table);
        rule.setTargetColumnName(column);
        rule.setEnabled(1);
        rule.setConfigVersion(1);
        rule.setSeverity(MetadataQualityRule.SEV_HIGH);
        rule.setDeductionPoints(new BigDecimal("5.0"));
        return rule;
    }

    private static DbTableMeta table(Long id, String tableName) {
        DbTableMeta table = new DbTableMeta();
        table.setId(id);
        table.setSnapshotId(1L);
        table.setDatasourceId(1L);
        table.setTableName(tableName);
        table.setTableType("TABLE");
        table.setTableComment(null);
        return table;
    }

    private static final class Fixture {
        private final MetadataSnapshot snapshot = new MetadataSnapshot();
        private final MetadataSnapshotMapper snapshotMapper = mock(MetadataSnapshotMapper.class);
        private final DbTableMetaMapper tableMapper = mock(DbTableMetaMapper.class);
        private final DbColumnMetaMapper columnMapper = mock(DbColumnMetaMapper.class);
        private final TableRelationMapper relationMapper = mock(TableRelationMapper.class);
        private final MetadataQualityIssueMapper issueMapper = mock(MetadataQualityIssueMapper.class);
        private final QualityCheckResultMapper checkResultMapper = mock(QualityCheckResultMapper.class);
        private final QualityRuleService ruleService = mock(QualityRuleService.class);
        private final QualityCheckBatchService batchService = mock(QualityCheckBatchService.class);
        private List<QualityChecker> checkers = List.of();

        Fixture() {
            when(snapshotMapper.selectById(anyLong())).thenAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                return id.equals(snapshot.getId()) ? snapshot : null;
            });
            when(batchService.begin(anyLong(), anyLong(), any(), any(), any(), any())).thenReturn(10L);
            when(tableMapper.selectList(any())).thenReturn(List.of());
            when(columnMapper.selectList(any())).thenReturn(List.of());
            when(relationMapper.selectList(any())).thenReturn(List.of());
        }

        void tables(List<DbTableMeta> tables) { when(tableMapper.selectList(any())).thenReturn(tables); }
        void rules(List<MetadataQualityRule> rules) { when(ruleService.listEnabledRules(1L)).thenReturn(rules); }

        QualityCheckServiceImpl service() {
            return new QualityCheckServiceImpl(checkers, ruleService, snapshotMapper, tableMapper,
                    columnMapper, relationMapper, issueMapper, checkResultMapper,
                    new QualityRuleTemplateRegistry(), batchService);
        }
    }
}
