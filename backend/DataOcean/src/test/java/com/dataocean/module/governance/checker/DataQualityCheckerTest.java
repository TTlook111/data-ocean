package com.dataocean.module.governance.checker;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.entity.po.Datasource;
import com.dataocean.module.datasource.entity.po.DatasourceSecret;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceSecretMapper;
import com.dataocean.module.datasource.service.DatasourceSecretService;
import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.mockito.MockedStatic;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

class DataQualityCheckerTest {

    @BeforeAll
    static void initTableInfo() {
        for (Class<?> entity : List.of(DatasourceSecret.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
    }

    @Test
    void dataRulesUseTheirExistingQualityDimensions() {
        DataQualityChecker checker = checker();

        org.assertj.core.api.Assertions.assertThat(checker.getSupportedDimensions())
                .containsExactlyInAnyOrder(
                        MetadataQualityRule.DIM_COMPLETENESS,
                        MetadataQualityRule.DIM_ACCURACY,
                        MetadataQualityRule.DIM_CONSISTENCY,
                        MetadataQualityRule.DIM_TIMELINESS);
    }

    @Test
    void disabledDatasourceReturnsChineseBusinessConflictInsteadOfEmptySuccess() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        Datasource disabled = new Datasource();
        disabled.setStatus(Datasource.STATUS_DISABLED);
        when(datasourceMapper.selectById(1L)).thenReturn(disabled);
        DataQualityChecker checker = new DataQualityChecker(
                datasourceMapper, mock(DatasourceSecretMapper.class), mock(DatasourceSecretService.class));

        MetadataQualityRule rule = new MetadataQualityRule();
        rule.setRuleCode("DATA_NULL_RATE_HIGH");
        rule.setDimension(MetadataQualityRule.DIM_COMPLETENESS);
        rule.setCheckType(MetadataQualityRule.CHECK_TYPE_DATA);
        rule.setEnabled(1);
        QualityChecker.CheckContext context = new QualityChecker.CheckContext(
                10L, 1L, List.of(), List.of(), List.of(), List.of(rule));

        assertThatThrownBy(() -> checker.check(context))
                .isInstanceOf(BusinessException.class)
                .hasMessage("数据源未启用，无法执行数据质量检查")
                .satisfies(exception -> org.assertj.core.api.Assertions.assertThat(
                        ((BusinessException) exception).getCode()).isEqualTo(400));
    }

    @Test
    void nullRateTemplateReportsAThresholdHitAndAWithinThresholdResult() throws Exception {
        assertNullRateResult(40L, 1);
        assertNullRateResult(10L, 0);
    }

    private void assertNullRateResult(long nullCount, int expectedIssues) throws Exception {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        Datasource datasource = new Datasource();
        datasource.setId(1L);
        datasource.setStatus(Datasource.STATUS_ENABLED);
        datasource.setHost("mysql.invalid");
        datasource.setPort(3306);
        datasource.setDatabaseName("readonly");
        when(datasourceMapper.selectById(1L)).thenReturn(datasource);

        DatasourceSecretMapper secretMapper = mock(DatasourceSecretMapper.class);
        DatasourceSecret secret = new DatasourceSecret();
        secret.setDatasourceId(1L);
        secret.setUsername("readonly");
        secret.setEncryptedPassword("encrypted");
        when(secretMapper.selectOne(any())).thenReturn(secret);
        DatasourceSecretService secretService = mock(DatasourceSecretService.class);
        when(secretService.decrypt("encrypted")).thenReturn("secret");

        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(any())).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getLong("total")).thenReturn(100L);
        when(rows.getLong("nulls")).thenReturn(nullCount);

        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(any(String.class), any(Properties.class)))
                    .thenReturn(connection);
            DataQualityChecker checker = new DataQualityChecker(datasourceMapper, secretMapper, secretService);
            MetadataQualityRule rule = new MetadataQualityRule();
            rule.setId(9L);
            rule.setRuleCode("DATA_NULL_RATE_HIGH");
            rule.setTemplateCode("DATA_NULL_RATE_HIGH");
            rule.setDimension(MetadataQualityRule.DIM_COMPLETENESS);
            rule.setCheckTarget(MetadataQualityRule.TARGET_COLUMN);
            rule.setCheckType(MetadataQualityRule.CHECK_TYPE_DATA);
            rule.setScopeType("COLUMN");
            rule.setEnabled(1);
            rule.setThreshold(new java.math.BigDecimal("0.3"));

            DbTableMeta table = new DbTableMeta();
            table.setSnapshotId(10L);
            table.setDatasourceId(1L);
            table.setTableName("orders");
            DbColumnMeta column = new DbColumnMeta();
            column.setSnapshotId(10L);
            column.setDatasourceId(1L);
            column.setTableName("orders");
            column.setColumnName("amount");
            column.setDataType("DECIMAL");

            List<?> issues = checker.check(new QualityChecker.CheckContext(
                    10L, 1L, List.of(table), List.of(column), List.of(), List.of(rule)));
            org.assertj.core.api.Assertions.assertThat(issues).hasSize(expectedIssues);
            org.mockito.Mockito.verify(statement).setQueryTimeout(30);
            org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.Mockito.verify(statement).executeQuery(sql.capture());
            org.assertj.core.api.Assertions.assertThat(sql.getValue()).contains("SELECT", "LIMIT 100000", "sample_value");
            org.mockito.Mockito.verify(connection).setReadOnly(true);
        }
    }

    private static DataQualityChecker checker() {
        return new DataQualityChecker(
                mock(DatasourceMapper.class), mock(DatasourceSecretMapper.class), mock(DatasourceSecretService.class));
    }
}
