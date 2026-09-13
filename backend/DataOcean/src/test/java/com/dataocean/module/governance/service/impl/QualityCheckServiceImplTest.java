package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.checker.QualityChecker;
import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.governance.mapper.QualityCheckResultMapper;
import com.dataocean.module.governance.service.QualityRuleService;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.entity.TableRelation;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

class QualityCheckServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        for (Class<?> entity : List.of(
                MetadataQualityIssue.class, DbTableMeta.class, DbColumnMeta.class, TableRelation.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entity);
        }
    }

    @Test
    void doesNotSwallowExpectedCheckerBusinessFailures() {
        QualityChecker checker = mock(QualityChecker.class);
        when(checker.getDimension()).thenReturn("COMPLETENESS");
        when(checker.getSupportedDimensions()).thenReturn(Set.of("COMPLETENESS"));
        when(checker.check(any())).thenThrow(new BusinessException(400, "数据源未启用，无法执行数据质量检查"));

        MetadataSnapshot snapshot = new MetadataSnapshot();
        snapshot.setId(10L);
        snapshot.setDatasourceId(1L);
        MetadataSnapshotMapper snapshotMapper = mock(MetadataSnapshotMapper.class);
        when(snapshotMapper.selectById(10L)).thenReturn(snapshot);

        MetadataQualityIssueMapper issueMapper = mock(MetadataQualityIssueMapper.class);
        QualityCheckServiceImpl service = new QualityCheckServiceImpl(
                List.of(checker),
                mock(QualityRuleService.class),
                snapshotMapper,
                mock(DbTableMetaMapper.class),
                mock(DbColumnMetaMapper.class),
                mock(TableRelationMapper.class),
                issueMapper,
                mock(QualityCheckResultMapper.class));

        assertThatThrownBy(() -> service.executeQualityCheck(10L, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("数据源未启用，无法执行数据质量检查")
                .satisfies(exception -> org.assertj.core.api.Assertions.assertThat(
                        ((BusinessException) exception).getCode()).isEqualTo(400));
        verify(issueMapper, never()).insert(any(MetadataQualityIssue.class));
    }

    @Test
    void postPublishQualityCheckKeepsPublishedSnapshotStatus() {
        MetadataSnapshot snapshot = new MetadataSnapshot();
        snapshot.setId(11L);
        snapshot.setDatasourceId(1L);
        snapshot.setStatus(MetadataSnapshot.STATUS_PUBLISHED);

        MetadataSnapshotMapper snapshotMapper = mock(MetadataSnapshotMapper.class);
        when(snapshotMapper.selectById(11L)).thenReturn(snapshot);
        QualityCheckServiceImpl service = new QualityCheckServiceImpl(
                List.of(),
                mock(QualityRuleService.class),
                snapshotMapper,
                mock(DbTableMetaMapper.class),
                mock(DbColumnMetaMapper.class),
                mock(TableRelationMapper.class),
                mock(MetadataQualityIssueMapper.class),
                mock(QualityCheckResultMapper.class));

        service.executeQualityCheck(11L, null, null);

        assertThat(snapshot.getStatus()).isEqualTo(MetadataSnapshot.STATUS_PUBLISHED);
    }
}
