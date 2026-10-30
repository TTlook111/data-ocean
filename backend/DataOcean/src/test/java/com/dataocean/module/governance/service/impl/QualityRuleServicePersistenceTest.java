package com.dataocean.module.governance.service.impl;

import com.dataocean.module.governance.entity.dto.QualityRuleDTO;
import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import com.dataocean.module.governance.entity.po.MetadataQualityRuleRevision;
import com.dataocean.module.governance.entity.query.QualityRuleQuery;
import com.dataocean.module.governance.mapper.MetadataQualityRuleMapper;
import com.dataocean.module.governance.mapper.MetadataQualityRuleRevisionMapper;
import com.dataocean.module.governance.service.QualityRuleTemplateRegistry;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 用独立 H2 数据库验证 MyBatis-Plus 实际更新后的规则状态，不连接本机业务库。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:quality_rule_persistence;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always"
})
@ActiveProfiles("test")
@Sql(statements = {
        "DROP TABLE IF EXISTS metadata_quality_rule",
        "CREATE TABLE metadata_quality_rule ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, rule_code VARCHAR(100), template_code VARCHAR(100), "
                + "rule_name VARCHAR(200), dimension VARCHAR(40), severity VARCHAR(20), description VARCHAR(500), "
                + "check_target VARCHAR(20), check_type VARCHAR(20), check_expression VARCHAR(500), threshold DECIMAL(12,4), "
                + "enabled INT, deduction_points DECIMAL(8,2), builtin INT, scope_type VARCHAR(30), datasource_id BIGINT, "
                + "target_table_name VARCHAR(255), target_column_name VARCHAR(255), parameters_json CLOB, config_version INT, "
                + "deleted_at TIMESTAMP, deleted_by BIGINT, created_by BIGINT, updated_by BIGINT, created_at TIMESTAMP, updated_at TIMESTAMP)"
})
class QualityRuleServicePersistenceTest {
    @Autowired
    private MetadataQualityRuleMapper ruleMapper;

    private MetadataQualityRuleRevisionMapper revisionMapper;
    private IamS1AuthorizationResolver authorizationResolver;

    @BeforeEach
    void prepareMocks() {
        revisionMapper = mock(MetadataQualityRuleRevisionMapper.class);
        authorizationResolver = mock(IamS1AuthorizationResolver.class);
        when(authorizationResolver.isSystemAdmin(1L)).thenReturn(true);
        when(authorizationResolver.canManageDatasource(9L, "governance:rule:manage", 5L)).thenReturn(true);
        when(authorizationResolver.canManageDatasource(9L, "governance:rule:manage", 6L)).thenReturn(true);
    }

    @Test
    void restoreActuallyClearsDeleteColumnsAndRevisionMatchesPersistedDisabledRule() {
        MetadataQualityRule deleted = rule("GLOBAL", null, null, null);
        deleted.setDeletedAt(LocalDateTime.now().minusDays(1));
        deleted.setDeletedBy(8L);
        deleted.setEnabled(1);
        ruleMapper.insert(deleted);

        service().restoreRule(deleted.getId(), 1L);

        MetadataQualityRule persisted = ruleMapper.selectById(deleted.getId());
        assertThat(persisted.getDeletedAt()).isNull();
        assertThat(persisted.getDeletedBy()).isNull();
        assertThat(persisted.getEnabled()).isZero();
        org.mockito.ArgumentCaptor<MetadataQualityRuleRevision> revisionCaptor =
                org.mockito.ArgumentCaptor.forClass(MetadataQualityRuleRevision.class);
        org.mockito.Mockito.verify(revisionMapper).insert(revisionCaptor.capture());
        assertThat(revisionCaptor.getValue().getDeleted()).isZero();
        assertThat(revisionCaptor.getValue().getEnabled()).isZero();
    }

    @Test
    void scopeChangeClearsPersistedDatasourceAndTargetsAndKeepsResponseAndRevisionAligned() {
        MetadataQualityRule existing = rule("COLUMN", 5L, "orders", "amount");
        ruleMapper.insert(existing);
        QualityRuleDTO replacement = dto("DATASOURCE", 6L, null, null);

        var response = service().updateRule(existing.getId(), replacement, 9L);

        MetadataQualityRule persisted = ruleMapper.selectById(existing.getId());
        assertThat(persisted.getScopeType()).isEqualTo("DATASOURCE");
        assertThat(persisted.getDatasourceId()).isEqualTo(6L);
        assertThat(persisted.getTargetTableName()).isNull();
        assertThat(persisted.getTargetColumnName()).isNull();
        assertThat(response.getDatasourceId()).isEqualTo(persisted.getDatasourceId());
        assertThat(response.getTargetTableName()).isEqualTo(persisted.getTargetTableName());
        assertThat(response.getTargetColumnName()).isEqualTo(persisted.getTargetColumnName());
        org.mockito.ArgumentCaptor<MetadataQualityRuleRevision> revisionCaptor =
                org.mockito.ArgumentCaptor.forClass(MetadataQualityRuleRevision.class);
        org.mockito.Mockito.verify(revisionMapper).insert(revisionCaptor.capture());
        assertThat(revisionCaptor.getValue().getDatasourceId()).isEqualTo(persisted.getDatasourceId());
        assertThat(revisionCaptor.getValue().getTargetTableName()).isEqualTo(persisted.getTargetTableName());
        assertThat(revisionCaptor.getValue().getTargetColumnName()).isEqualTo(persisted.getTargetColumnName());
    }

    @Test
    void changingColumnRuleToGlobalActuallyClearsDatasourceAndBothTargetFields() {
        MetadataQualityRule existing = rule("COLUMN", 5L, "orders", "amount");
        ruleMapper.insert(existing);
        when(authorizationResolver.isSystemAdmin(9L)).thenReturn(true);

        var response = service().updateRule(existing.getId(), dto("GLOBAL", null, null, null), 9L);

        MetadataQualityRule persisted = ruleMapper.selectById(existing.getId());
        assertThat(persisted.getScopeType()).isEqualTo("GLOBAL");
        assertThat(persisted.getDatasourceId()).isNull();
        assertThat(persisted.getTargetTableName()).isNull();
        assertThat(persisted.getTargetColumnName()).isNull();
        assertThat(response.getDatasourceId()).isNull();
        assertThat(response.getTargetTableName()).isNull();
        assertThat(response.getTargetColumnName()).isNull();
        org.mockito.ArgumentCaptor<MetadataQualityRuleRevision> revisionCaptor =
                org.mockito.ArgumentCaptor.forClass(MetadataQualityRuleRevision.class);
        org.mockito.Mockito.verify(revisionMapper).insert(revisionCaptor.capture());
        assertThat(revisionCaptor.getValue().getDatasourceId()).isNull();
        assertThat(revisionCaptor.getValue().getTargetTableName()).isNull();
        assertThat(revisionCaptor.getValue().getTargetColumnName()).isNull();
    }

    @Test
    void listRulesReturnsOnlyTheRequestedPageWithTotalCount() {
        for (int index = 0; index < 25; index++) {
            MetadataQualityRule rule = rule("GLOBAL", null, null, null);
            rule.setRuleCode("QR-PAGE-" + index);
            rule.setRuleName("分页规则 " + index);
            ruleMapper.insert(rule);
        }
        IamS1CapabilityService capabilityService = mock(IamS1CapabilityService.class);
        when(capabilityService.responsibleDatasourcesWithFunction(1L, "governance:rule:view"))
                .thenReturn(List.of());
        QualityRuleServiceImpl service = new QualityRuleServiceImpl(ruleMapper, revisionMapper, authorizationResolver,
                capabilityService, new QualityRuleTemplateRegistry(),
                mock(MetadataSnapshotMapper.class), mock(DbTableMetaMapper.class),
                mock(DbColumnMetaMapper.class), new ObjectMapper());
        QualityRuleQuery query = new QualityRuleQuery();
        query.setPage(2L);
        query.setPageSize(10L);

        var page = service.listRules(query, 1L);

        assertThat(page.getTotal()).isEqualTo(25);
        assertThat(page.getRecords()).hasSize(10);
        assertThat(page.getCurrent()).isEqualTo(2);
    }

    private QualityRuleServiceImpl service() {
        return new QualityRuleServiceImpl(ruleMapper, revisionMapper, authorizationResolver,
                mock(IamS1CapabilityService.class), new QualityRuleTemplateRegistry(),
                mock(MetadataSnapshotMapper.class), mock(DbTableMetaMapper.class),
                mock(DbColumnMetaMapper.class), new ObjectMapper());
    }

    private static MetadataQualityRule rule(String scope, Long datasourceId, String table, String column) {
        MetadataQualityRule rule = new MetadataQualityRule();
        rule.setRuleCode("QR-PERSIST-" + scope);
        rule.setTemplateCode("COMP_TABLE_COMMENT_MISSING");
        rule.setRuleName("可持久化规则");
        rule.setDimension(MetadataQualityRule.DIM_COMPLETENESS);
        rule.setSeverity("HIGH");
        rule.setCheckTarget(MetadataQualityRule.TARGET_TABLE);
        rule.setCheckType(MetadataQualityRule.CHECK_TYPE_SCHEMA);
        rule.setScopeType(scope);
        rule.setDatasourceId(datasourceId);
        rule.setTargetTableName(table);
        rule.setTargetColumnName(column);
        rule.setParametersJson("{}");
        rule.setEnabled(0);
        rule.setDeductionPoints(new BigDecimal("5"));
        rule.setConfigVersion(2);
        rule.setBuiltin(0);
        rule.setCreatedBy(9L);
        rule.setUpdatedBy(9L);
        return rule;
    }

    private static QualityRuleDTO dto(String scope, Long datasourceId, String table, String column) {
        QualityRuleDTO dto = new QualityRuleDTO();
        dto.setRuleName("修改后的规则");
        dto.setTemplateCode("COMP_TABLE_COMMENT_MISSING");
        dto.setScopeType(scope);
        dto.setDatasourceId(datasourceId);
        dto.setTargetTableName(table);
        dto.setTargetColumnName(column);
        dto.setSeverity("MEDIUM");
        dto.setDeductionPoints(new BigDecimal("2.5"));
        dto.setEnabled(false);
        dto.setParameters(Map.of());
        return dto;
    }
}
