package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.entity.dto.QualityRuleDTO;
import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import com.dataocean.module.governance.entity.po.MetadataQualityRuleRevision;
import com.dataocean.module.governance.entity.vo.QualityRuleRevisionVO;
import com.dataocean.module.governance.mapper.MetadataQualityRuleMapper;
import com.dataocean.module.governance.mapper.MetadataQualityRuleRevisionMapper;
import com.dataocean.module.governance.service.QualityRuleTemplateRegistry;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityRuleServiceImplTest {
    @BeforeAll
    static void initTableInfo() {
        for (Class<?> entity : List.of(MetadataQualityRule.class, MetadataQualityRuleRevision.class, MetadataSnapshot.class,
                DbTableMeta.class, DbColumnMeta.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
    }

    @Test
    void globalWriteRequiresProtectedSystemAdminEvenIfRuleManageFunctionExists() {
        Fixture fixture = new Fixture();
        when(fixture.authorizationResolver.isSystemAdmin(9L)).thenReturn(false);

        assertThatThrownBy(() -> fixture.service.createRule(rule("GLOBAL", null, null, null), 9L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getCode()).isEqualTo(403));
        verify(fixture.ruleMapper, never()).insert(any(MetadataQualityRule.class));
    }

    @Test
    void sourceRuleWriteRequiresTheSameIamResponsibleDatasourceBinding() {
        Fixture fixture = new Fixture();
        when(fixture.authorizationResolver.canManageDatasource(9L, "governance:rule:manage", 5L)).thenReturn(false);

        QualityRuleDTO request = rule("DATASOURCE", 5L, null, null);
        assertThatThrownBy(() -> fixture.service.createRule(request, 9L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getCode()).isEqualTo(403));
        verify(fixture.ruleMapper, never()).insert(any(MetadataQualityRule.class));
    }

    @Test
    void enabledRuleWithARegisteredTemplateIsPersistedAsAnIndependentInstance() {
        Fixture fixture = new Fixture();
        when(fixture.authorizationResolver.isSystemAdmin(1L)).thenReturn(true);
        when(fixture.ruleMapper.insert(any(MetadataQualityRule.class))).thenAnswer(invocation -> {
            MetadataQualityRule rule = invocation.getArgument(0);
            rule.setId(100L);
            return 1;
        });
        QualityRuleDTO request = rule("GLOBAL", null, null, null);
        request.setTemplateCode("DATA_NULL_RATE_HIGH");
        request.setParameters(Map.of("threshold", new BigDecimal("0.42")));
        request.setEnabled(true);

        var created = fixture.service.createRule(request, 1L);

        assertThat(created.getRuleCode()).startsWith("QR-");
        assertThat(created.getTemplateCode()).isEqualTo("DATA_NULL_RATE_HIGH");
        assertThat(created.getConfigVersion()).isEqualTo(1);
        assertThat(created.getEnabled()).isEqualTo(1);
        verify(fixture.ruleMapper).insert(any(MetadataQualityRule.class));
        org.mockito.ArgumentCaptor<MetadataQualityRuleRevision> captor =
                org.mockito.ArgumentCaptor.forClass(MetadataQualityRuleRevision.class);
        verify(fixture.revisionMapper).insert(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("CREATE");
        assertThat(captor.getValue().getTemplateCode()).isEqualTo("DATA_NULL_RATE_HIGH");
        assertThat(captor.getValue().getParametersJson()).contains("0.42");
        assertThat(captor.getValue().getChangedBy()).isEqualTo(1L);
    }

    @Test
    void sameTemplateAndSameLevelOverlapIsRejectedBeforeEnabling() {
        Fixture fixture = new Fixture();
        when(fixture.authorizationResolver.isSystemAdmin(1L)).thenReturn(true);
        when(fixture.ruleMapper.selectList(any())).thenReturn(List.of(new MetadataQualityRule()));
        QualityRuleDTO request = rule("GLOBAL", null, null, null);
        request.setEnabled(true);

        assertThatThrownBy(() -> fixture.service.createRule(request, 1L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getCode()).isEqualTo(409))
                .hasMessageContaining("同一检查模板");
        verify(fixture.ruleMapper, never()).insert(any(MetadataQualityRule.class));
    }

    @Test
    void tableAndColumnScopesArePersistedByStableNamesAndVerifiedAgainstRealSnapshot() {
        Fixture fixture = new Fixture();
        when(fixture.authorizationResolver.canManageDatasource(9L, "governance:rule:manage", 5L)).thenReturn(true);
        when(fixture.ruleMapper.insert(any(MetadataQualityRule.class))).thenAnswer(invocation -> {
            ((MetadataQualityRule) invocation.getArgument(0)).setId(101L);
            return 1;
        });
        MetadataSnapshot latest = new MetadataSnapshot();
        latest.setId(77L);
        latest.setDatasourceId(5L);
        when(fixture.snapshotMapper.selectOne(any())).thenReturn(latest);
        DbTableMeta table = new DbTableMeta();
        table.setTableName("orders");
        when(fixture.tableMapper.selectOne(any())).thenReturn(table);
        DbColumnMeta column = new DbColumnMeta();
        column.setColumnName("order_id");
        column.setGovernanceStatus("NORMAL");
        when(fixture.columnMapper.selectOne(any())).thenReturn(column);

        QualityRuleDTO request = rule("COLUMN", 5L, "orders", "order_id");
        request.setTemplateCode("DATA_NULL_RATE_HIGH");
        request.setParameters(Map.of("threshold", 0.2));
        request.setEnabled(false);

        var created = fixture.service.createRule(request, 9L);

        assertThat(created.getScopeType()).isEqualTo("COLUMN");
        assertThat(created.getDatasourceId()).isEqualTo(5L);
        assertThat(created.getTargetTableName()).isEqualTo("orders");
        assertThat(created.getTargetColumnName()).isEqualTo("order_id");
        assertThat(created.getParameters()).containsEntry("threshold", 0.2);
    }

    @Test
    void editIncrementsConfigurationVersionAndWritesAnImmutableRevision() {
        Fixture fixture = new Fixture();
        MetadataQualityRule existing = new MetadataQualityRule();
        existing.setId(15L);
        existing.setRuleCode("QR-15");
        existing.setTemplateCode("COMP_TABLE_COMMENT_MISSING");
        existing.setRuleName("旧规则名称");
        existing.setDimension(MetadataQualityRule.DIM_COMPLETENESS);
        existing.setCheckTarget(MetadataQualityRule.TARGET_TABLE);
        existing.setCheckType(MetadataQualityRule.CHECK_TYPE_SCHEMA);
        existing.setScopeType("GLOBAL");
        existing.setSeverity("HIGH");
        existing.setDeductionPoints(new BigDecimal("5.0"));
        existing.setEnabled(0);
        existing.setConfigVersion(3);
        existing.setBuiltin(1);
        when(fixture.ruleMapper.selectById(15L)).thenReturn(existing);
        when(fixture.ruleMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(fixture.authorizationResolver.isSystemAdmin(1L)).thenReturn(true);
        QualityRuleDTO request = rule("GLOBAL", null, null, null);
        request.setRuleName("新规则名称");

        fixture.service.updateRule(15L, request, 1L);

        assertThat(existing.getRuleName()).isEqualTo("新规则名称");
        assertThat(existing.getConfigVersion()).isEqualTo(4);
        verify(fixture.ruleMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        org.mockito.ArgumentCaptor<MetadataQualityRuleRevision> captor =
                org.mockito.ArgumentCaptor.forClass(MetadataQualityRuleRevision.class);
        verify(fixture.revisionMapper).insert(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("UPDATE");
        assertThat(captor.getValue().getConfigVersion()).isEqualTo(4);
        assertThat(captor.getValue().getRuleName()).isEqualTo("新规则名称");
    }

    @Test
    void changingDatasourceScopeChecksBothOriginalAndTargetResponsibleDatasource() {
        Fixture fixture = new Fixture();
        MetadataQualityRule existing = new MetadataQualityRule();
        existing.setId(16L);
        existing.setRuleCode("QR-16");
        existing.setTemplateCode("COMP_TABLE_COMMENT_MISSING");
        existing.setRuleName("源规则");
        existing.setDimension(MetadataQualityRule.DIM_COMPLETENESS);
        existing.setCheckTarget(MetadataQualityRule.TARGET_TABLE);
        existing.setCheckType(MetadataQualityRule.CHECK_TYPE_SCHEMA);
        existing.setScopeType("DATASOURCE");
        existing.setDatasourceId(5L);
        existing.setSeverity("HIGH");
        existing.setDeductionPoints(new BigDecimal("5.0"));
        existing.setEnabled(0);
        existing.setConfigVersion(1);
        when(fixture.ruleMapper.selectById(16L)).thenReturn(existing);
        when(fixture.authorizationResolver.canManageDatasource(9L, "governance:rule:manage", 5L)).thenReturn(true);
        when(fixture.authorizationResolver.canManageDatasource(9L, "governance:rule:manage", 6L)).thenReturn(false);
        QualityRuleDTO replacement = rule("DATASOURCE", 6L, null, null);

        assertThatThrownBy(() -> fixture.service.updateRule(16L, replacement, 9L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getCode()).isEqualTo(403));
        verify(fixture.ruleMapper, never()).updateById(any(MetadataQualityRule.class));
        verify(fixture.ruleMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(fixture.revisionMapper, never()).insert(any(MetadataQualityRuleRevision.class));
    }

    @Test
    void copyCreatesAnIndependentDisabledInstanceAndRevision() {
        Fixture fixture = new Fixture();
        MetadataQualityRule source = new MetadataQualityRule();
        source.setId(20L);
        source.setRuleCode("BUILTIN-20");
        source.setTemplateCode("COMP_TABLE_COMMENT_MISSING");
        source.setRuleName("表注释");
        source.setDimension(MetadataQualityRule.DIM_COMPLETENESS);
        source.setCheckTarget(MetadataQualityRule.TARGET_TABLE);
        source.setCheckType(MetadataQualityRule.CHECK_TYPE_SCHEMA);
        source.setSeverity("HIGH");
        source.setDeductionPoints(new BigDecimal("5.0"));
        source.setScopeType("GLOBAL");
        source.setEnabled(1);
        source.setBuiltin(1);
        source.setConfigVersion(3);
        when(fixture.ruleMapper.selectById(20L)).thenReturn(source);
        when(fixture.authorizationResolver.isSystemAdmin(1L)).thenReturn(true);
        when(fixture.ruleMapper.insert(any(MetadataQualityRule.class))).thenAnswer(invocation -> {
            ((MetadataQualityRule) invocation.getArgument(0)).setId(21L);
            return 1;
        });

        var copy = fixture.service.copyRule(20L, 1L);

        assertThat(copy.getId()).isEqualTo(21L);
        assertThat(copy.getRuleCode()).isNotEqualTo(source.getRuleCode());
        assertThat(copy.getEnabled()).isZero();
        assertThat(copy.getBuiltin()).isZero();
        assertThat(copy.getConfigVersion()).isEqualTo(1);
        org.mockito.ArgumentCaptor<MetadataQualityRuleRevision> captor =
                org.mockito.ArgumentCaptor.forClass(MetadataQualityRuleRevision.class);
        verify(fixture.revisionMapper).insert(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("COPY");
    }

    @Test
    void deletingAnEnabledRuleAtomicallyDisablesAndSoftDeletesIt() {
        Fixture fixture = new Fixture();
        MetadataQualityRule rule = new MetadataQualityRule();
        rule.setId(8L);
        rule.setEnabled(1);
        rule.setScopeType("GLOBAL");
        when(fixture.ruleMapper.selectById(8L)).thenReturn(rule);
        when(fixture.authorizationResolver.isSystemAdmin(1L)).thenReturn(true);

        fixture.service.deleteRule(8L, 1L);

        assertThat(rule.getEnabled()).isZero();
        assertThat(rule.getDeletedAt()).isNotNull();
        assertThat(rule.getDeletedBy()).isEqualTo(1L);
        verify(fixture.ruleMapper).updateById(rule);
    }

    @Test
    void restoredRuleReturnsDisabledAndDoesNotReenableWithoutConflictValidation() {
        Fixture fixture = new Fixture();
        MetadataQualityRule rule = new MetadataQualityRule();
        rule.setId(8L);
        rule.setEnabled(0);
        rule.setScopeType("GLOBAL");
        rule.setDeletedAt(java.time.LocalDateTime.now());
        when(fixture.ruleMapper.selectById(8L)).thenReturn(rule);
        when(fixture.ruleMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(fixture.authorizationResolver.isSystemAdmin(1L)).thenReturn(true);

        fixture.service.restoreRule(8L, 1L);

        assertThat(rule.getDeletedAt()).isNull();
        assertThat(rule.getEnabled()).isZero();
        verify(fixture.ruleMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void revisionHistoryIsFilteredByEachRevisionDatasource() {
        Fixture fixture = new Fixture();
        MetadataQualityRule current = datasourceRule(30L, 6L);
        when(fixture.ruleMapper.selectById(30L)).thenReturn(current);
        when(fixture.capabilityService.responsibleDatasourcesWithFunction(9L, "governance:rule:view"))
                .thenReturn(List.of(new IamS1DatasourceRefVO(6L, "数据源 B", true)));
        when(fixture.revisionMapper.selectList(any())).thenReturn(List.of(
                revision(1L, 30L, 5L), revision(2L, 30L, 6L)));

        List<QualityRuleRevisionVO> history = fixture.service.history(30L, 9L);

        assertThat(history).extracting(QualityRuleRevisionVO::getDatasourceId).containsExactly(6L);
    }

    @Test
    void revisionHistoryIncludesBothSourcesForUsersAuthorizedOnBoth() {
        Fixture fixture = new Fixture();
        when(fixture.ruleMapper.selectById(31L)).thenReturn(datasourceRule(31L, 6L));
        when(fixture.capabilityService.responsibleDatasourcesWithFunction(10L, "governance:rule:view"))
                .thenReturn(List.of(new IamS1DatasourceRefVO(5L, "数据源 A", true),
                        new IamS1DatasourceRefVO(6L, "数据源 B", true)));
        when(fixture.revisionMapper.selectList(any())).thenReturn(List.of(
                revision(3L, 31L, 5L), revision(4L, 31L, 6L)));

        List<QualityRuleRevisionVO> history = fixture.service.history(31L, 10L);

        assertThat(history).extracting(QualityRuleRevisionVO::getDatasourceId).containsExactly(5L, 6L);
    }

    @Test
    void revisionHistoryRejectsUserWithoutCurrentDatasourceAccess() {
        Fixture fixture = new Fixture();
        when(fixture.ruleMapper.selectById(32L)).thenReturn(datasourceRule(32L, 6L));
        when(fixture.capabilityService.responsibleDatasourcesWithFunction(11L, "governance:rule:view"))
                .thenReturn(List.of(new IamS1DatasourceRefVO(5L, "数据源 A", true)));

        assertThatThrownBy(() -> fixture.service.history(32L, 11L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getCode()).isEqualTo(403));
    }

    @Test
    void protectedSystemAdminCanReadHistoricalDatasourceRevisions() {
        Fixture fixture = new Fixture();
        when(fixture.ruleMapper.selectById(33L)).thenReturn(datasourceRule(33L, 6L));
        when(fixture.authorizationResolver.isSystemAdmin(12L)).thenReturn(true);
        when(fixture.capabilityService.responsibleDatasourcesWithFunction(12L, "governance:rule:view"))
                .thenReturn(List.of(new IamS1DatasourceRefVO(6L, "数据源 B", true)));
        when(fixture.revisionMapper.selectList(any())).thenReturn(List.of(
                revision(5L, 33L, 5L), revision(6L, 33L, 6L)));

        List<QualityRuleRevisionVO> history = fixture.service.history(33L, 12L);

        assertThat(history).extracting(QualityRuleRevisionVO::getDatasourceId).containsExactly(5L, 6L);
    }

    @Test
    void globalRuleHistoryRemainsVisibleAlongsideOnlyAuthorizedDatasourceRevisions() {
        Fixture fixture = new Fixture();
        MetadataQualityRule global = datasourceRule(34L, null);
        global.setScopeType("GLOBAL");
        when(fixture.ruleMapper.selectById(34L)).thenReturn(global);
        when(fixture.capabilityService.responsibleDatasourcesWithFunction(13L, "governance:rule:view"))
                .thenReturn(List.of(new IamS1DatasourceRefVO(6L, "数据源 B", true)));
        MetadataQualityRuleRevision globalRevision = revision(7L, 34L, null);
        globalRevision.setScopeType("GLOBAL");
        when(fixture.revisionMapper.selectList(any())).thenReturn(List.of(
                revision(8L, 34L, 5L), globalRevision, revision(9L, 34L, 6L)));

        List<QualityRuleRevisionVO> history = fixture.service.history(34L, 13L);

        assertThat(history).extracting(QualityRuleRevisionVO::getDatasourceId).containsExactly(null, 6L);
    }

    private static MetadataQualityRule datasourceRule(Long id, Long datasourceId) {
        MetadataQualityRule rule = new MetadataQualityRule();
        rule.setId(id);
        rule.setScopeType("DATASOURCE");
        rule.setDatasourceId(datasourceId);
        rule.setEnabled(0);
        return rule;
    }

    private static MetadataQualityRuleRevision revision(Long id, Long ruleId, Long datasourceId) {
        MetadataQualityRuleRevision revision = new MetadataQualityRuleRevision();
        revision.setId(id);
        revision.setRuleId(ruleId);
        revision.setRuleCode("QR-" + ruleId);
        revision.setTemplateCode("COMP_TABLE_COMMENT_MISSING");
        revision.setConfigVersion(id.intValue());
        revision.setAction("UPDATE");
        revision.setRuleName("历史规则 " + datasourceId);
        revision.setDimension(MetadataQualityRule.DIM_COMPLETENESS);
        revision.setSeverity("HIGH");
        revision.setCheckTarget(MetadataQualityRule.TARGET_TABLE);
        revision.setCheckType(MetadataQualityRule.CHECK_TYPE_SCHEMA);
        revision.setScopeType("DATASOURCE");
        revision.setDatasourceId(datasourceId);
        revision.setTargetTableName("table_" + datasourceId);
        revision.setParametersJson("{}");
        revision.setEnabled(0);
        revision.setDeductionPoints(BigDecimal.ONE);
        revision.setDeleted(0);
        revision.setChangedAt(java.time.LocalDateTime.now());
        return revision;
    }

    private static QualityRuleDTO rule(String scope, Long datasourceId, String tableName, String columnName) {
        QualityRuleDTO dto = new QualityRuleDTO();
        dto.setRuleName("测试质量规则");
        dto.setTemplateCode("COMP_TABLE_COMMENT_MISSING");
        dto.setScopeType(scope);
        dto.setDatasourceId(datasourceId);
        dto.setTargetTableName(tableName);
        dto.setTargetColumnName(columnName);
        dto.setSeverity("HIGH");
        dto.setDeductionPoints(new BigDecimal("5.0"));
        dto.setEnabled(false);
        return dto;
    }

    private static final class Fixture {
        private final MetadataQualityRuleMapper ruleMapper = mock(MetadataQualityRuleMapper.class);
        private final MetadataQualityRuleRevisionMapper revisionMapper = mock(MetadataQualityRuleRevisionMapper.class);
        private final IamS1AuthorizationResolver authorizationResolver = mock(IamS1AuthorizationResolver.class);
        private final IamS1CapabilityService capabilityService = mock(IamS1CapabilityService.class);
        private final MetadataSnapshotMapper snapshotMapper = mock(MetadataSnapshotMapper.class);
        private final DbTableMetaMapper tableMapper = mock(DbTableMetaMapper.class);
        private final DbColumnMetaMapper columnMapper = mock(DbColumnMetaMapper.class);
        private final QualityRuleServiceImpl service = new QualityRuleServiceImpl(ruleMapper, revisionMapper, authorizationResolver,
                capabilityService, new QualityRuleTemplateRegistry(), snapshotMapper, tableMapper,
                columnMapper, new ObjectMapper());
    }
}
