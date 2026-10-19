package com.dataocean.module.governance.service;

import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QualityRuleExecutionPlannerTest {
    private final QualityRuleTemplateRegistry registry = new QualityRuleTemplateRegistry();
    private final QualityRuleExecutionPlanner planner = new QualityRuleExecutionPlanner(registry);

    @Test
    void registryOnlyExposesTheFifteenTemplatesBackedByExistingCheckers() {
        assertThat(registry.all()).hasSize(15);
        assertThat(registry.all()).allSatisfy(template -> assertThat(template.getHandlerDimension()).isNotBlank());
        assertThat(registry.find("FREE_SQL")).isNull();
        assertThat(registry.require("DATA_NULL_RATE_HIGH").getParameters())
                .extracting(QualityRuleTemplateRegistry.ParameterDefinition::getKey)
                .containsExactly("threshold");
    }

    @Test
    void overlappingInstancesResolveOncePerColumnFromMostSpecificScope() {
        MetadataQualityRule global = rule(1L, "GLOBAL", null, null);
        MetadataQualityRule datasource = rule(2L, "DATASOURCE", null, null);
        datasource.setDatasourceId(5L);
        MetadataQualityRule table = rule(3L, "TABLE", "orders", null);
        table.setDatasourceId(5L);
        MetadataQualityRule column = rule(4L, "COLUMN", "orders", "order_id");
        column.setDatasourceId(5L);
        List<DbTableMeta> tables = List.of(table(1L, "orders"), table(2L, "customers"));
        List<DbColumnMeta> columns = List.of(column("orders", "order_id"), column("orders", "amount"),
                column("customers", "customer_id"));

        List<QualityRuleExecutionPlanner.Execution> plan = planner.plan(5L,
                List.of(global, datasource, table, column), tables, columns, List.of(), tables, columns, List.of(), null);

        assertThat(plan).filteredOn(e -> "READY".equals(e.status())).hasSize(3);
        assertThat(plan.stream().mapToInt(e -> e.columns().size()).sum()).isEqualTo(3);
        assertThat(plan.stream().filter(e -> e.rule().getId().equals(1L)).findFirst().orElseThrow().status())
                .isEqualTo("NOT_APPLICABLE");
        assertThat(plan.stream().filter(e -> e.rule().getId().equals(2L)).findFirst().orElseThrow().columns())
                .extracting(DbColumnMeta::getColumnName).containsExactly("customer_id");
        assertThat(plan.stream().filter(e -> e.rule().getId().equals(3L)).findFirst().orElseThrow().columns())
                .extracting(DbColumnMeta::getColumnName).containsExactly("amount");
        assertThat(plan.stream().filter(e -> e.rule().getId().equals(4L)).findFirst().orElseThrow().columns())
                .extracting(DbColumnMeta::getColumnName).containsExactly("order_id");
        assertThat(plan.stream().filter(e -> e.rule().getId().equals(1L)).findFirst().orElseThrow().targetCount())
                .isEqualTo(3);
    }

    @Test
    void sameLevelConflictFailsInsteadOfArbitrarilyChoosingOneRule() {
        MetadataQualityRule first = rule(5L, "COLUMN", "orders", "order_id");
        first.setDatasourceId(5L);
        MetadataQualityRule second = rule(6L, "COLUMN", "orders", "order_id");
        second.setDatasourceId(5L);
        List<DbTableMeta> tables = List.of(table(1L, "orders"));
        List<DbColumnMeta> columns = List.of(column("orders", "order_id"));

        List<QualityRuleExecutionPlanner.Execution> plan = planner.plan(5L, List.of(first, second),
                tables, columns, List.of(), tables, columns, List.of(), null);

        assertThat(plan).extracting(QualityRuleExecutionPlanner.Execution::status).containsExactly("FAILURE", "FAILURE");
        assertThat(plan).allSatisfy(execution -> assertThat(execution.reason()).contains("冲突"));
    }

    @Test
    void missingStableObjectIsReportedAsNotApplicableAndBlocking() {
        MetadataQualityRule column = rule(7L, "COLUMN", "orders", "missing_field");
        column.setDatasourceId(5L);
        List<DbTableMeta> tables = List.of(table(1L, "orders"));
        List<DbColumnMeta> columns = List.of(column("orders", "order_id"));

        List<QualityRuleExecutionPlanner.Execution> plan = planner.plan(5L, List.of(column),
                tables, columns, List.of(), tables, columns, List.of(), null);

        assertThat(plan.get(0).status()).isEqualTo("NOT_APPLICABLE");
        assertThat(plan.get(0).blocking()).isTrue();
        assertThat(plan.get(0).reason()).contains("字段").contains("不存在");
    }

    @Test
    void uniqueTemplateCanTargetAnExplicitNonPrimaryColumnWhenConfigured() {
        MetadataQualityRule unique = rule(8L, "COLUMN", "orders", "external_code");
        unique.setTemplateCode("DATA_UNIQUE_VIOLATION");
        unique.setRuleCode("UNIQUE-INSTANCE");
        unique.setDimension(MetadataQualityRule.DIM_ACCURACY);
        unique.setDatasourceId(5L);
        unique.setParametersJson("{\"primaryKeyOnly\":false}");
        List<DbTableMeta> tables = List.of(table(1L, "orders"));
        List<DbColumnMeta> columns = List.of(column("orders", "external_code"));

        List<QualityRuleExecutionPlanner.Execution> plan = planner.plan(5L, List.of(unique),
                tables, columns, List.of(), tables, columns, List.of(), null);

        assertThat(plan.get(0).status()).isEqualTo("READY");
        assertThat(plan.get(0).columns()).extracting(DbColumnMeta::getColumnName).containsExactly("external_code");
    }

    @Test
    void dataRuleReportsRestrictedTargetsAndBlocksCompleteCoverageWithoutQueryingThem() {
        MetadataQualityRule global = rule(9L, "GLOBAL", null, null);
        DbTableMeta allowed = table(1L, "orders");
        DbTableMeta blocked = table(2L, "audit_archive");
        blocked.setGovernanceStatus("BLOCKED");
        List<DbTableMeta> tables = List.of(allowed, blocked);
        List<DbColumnMeta> columns = List.of(column("orders", "amount"), column("audit_archive", "amount"));

        List<QualityRuleExecutionPlanner.Execution> plan = planner.plan(5L, List.of(global),
                tables, columns, List.of(), tables, columns, List.of(), null);

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).status()).isEqualTo("READY");
        assertThat(plan.get(0).columns()).extracting(DbColumnMeta::getTableName).containsExactly("orders");
        assertThat(plan.get(0).targetCount()).isEqualTo(2);
        assertThat(plan.get(0).checkedTargetCount()).isEqualTo(1);
        assertThat(plan.get(0).blocking()).isTrue();
        assertThat(plan.get(0).reason()).contains("废弃或阻断");
    }

    private static MetadataQualityRule rule(Long id, String scope, String table, String column) {
        MetadataQualityRule rule = new MetadataQualityRule();
        rule.setId(id);
        rule.setRuleCode("INSTANCE-" + id);
        rule.setTemplateCode("DATA_NULL_RATE_HIGH");
        rule.setRuleName("空值率实例 " + id);
        rule.setDimension(MetadataQualityRule.DIM_COMPLETENESS);
        rule.setCheckTarget(MetadataQualityRule.TARGET_COLUMN);
        rule.setCheckType(MetadataQualityRule.CHECK_TYPE_DATA);
        rule.setScopeType(scope);
        rule.setTargetTableName(table);
        rule.setTargetColumnName(column);
        rule.setEnabled(1);
        rule.setConfigVersion(1);
        rule.setSeverity("MEDIUM");
        rule.setDeductionPoints(new BigDecimal("3.0"));
        return rule;
    }

    private static DbTableMeta table(Long id, String name) {
        DbTableMeta table = new DbTableMeta();
        table.setId(id);
        table.setTableName(name);
        return table;
    }

    private static DbColumnMeta column(String tableName, String columnName) {
        DbColumnMeta column = new DbColumnMeta();
        column.setTableName(tableName);
        column.setColumnName(columnName);
        column.setDataType("VARCHAR(32)");
        return column;
    }
}
