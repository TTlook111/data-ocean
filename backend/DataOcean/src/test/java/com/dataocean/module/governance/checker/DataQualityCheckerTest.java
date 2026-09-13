package com.dataocean.module.governance.checker;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceSecretMapper;
import com.dataocean.module.datasource.service.DatasourceSecretService;
import com.dataocean.module.governance.entity.MetadataQualityRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataQualityCheckerTest {

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

    private static DataQualityChecker checker() {
        return new DataQualityChecker(
                mock(DatasourceMapper.class), mock(DatasourceSecretMapper.class), mock(DatasourceSecretService.class));
    }
}
