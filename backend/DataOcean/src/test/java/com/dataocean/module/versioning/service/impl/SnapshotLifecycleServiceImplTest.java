package com.dataocean.module.versioning.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.governance.entity.po.MetadataQualityIssue;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.service.SchemaDiffService;
import com.dataocean.module.user.service.UserService;
import com.dataocean.module.versioning.service.SnapshotAuditLogService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnapshotLifecycleServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MetadataSnapshot.class
        );
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MetadataQualityIssue.class
        );
    }

    @Test
    void listVersionHistoryDoesNotApplyDatasourceFilterWhenDatasourceIdIsMissing() {
        MetadataSnapshotMapper snapshotMapper = mock(MetadataSnapshotMapper.class);
        MetadataQualityIssueMapper qualityIssueMapper = mock(MetadataQualityIssueMapper.class);
        DbTableMetaMapper tableMetaMapper = mock(DbTableMetaMapper.class);
        SchemaDiffService schemaDiffService = mock(SchemaDiffService.class);
        SnapshotAuditLogService auditLogService = mock(SnapshotAuditLogService.class);
        UserService userService = mock(UserService.class);
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        SnapshotLifecycleServiceImpl service = new SnapshotLifecycleServiceImpl(
                snapshotMapper,
                qualityIssueMapper,
                tableMetaMapper,
                schemaDiffService,
                auditLogService,
                userService,
                datasourceMapper
        );

        when(snapshotMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenReturn(new Page<MetadataSnapshot>().setRecords(List.of()));
        when(datasourceMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.listVersionHistory(null, 1, 20);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Wrapper<MetadataSnapshot>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(snapshotMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getCustomSqlSegment())
                .doesNotContain("datasource_id");
    }

    @Test
    void sourceHighIssuesStillGateApprovalAndTheQueryIsExplicitlyScopedToSourceDomain() {
        MetadataSnapshotMapper snapshotMapper = mock(MetadataSnapshotMapper.class);
        MetadataQualityIssueMapper qualityIssueMapper = mock(MetadataQualityIssueMapper.class);
        SnapshotLifecycleServiceImpl service = new SnapshotLifecycleServiceImpl(
                snapshotMapper, qualityIssueMapper, mock(DbTableMetaMapper.class),
                mock(SchemaDiffService.class), mock(SnapshotAuditLogService.class),
                mock(UserService.class), mock(DatasourceMapper.class));
        MetadataSnapshot snapshot = new MetadataSnapshot();
        snapshot.setId(80L); snapshot.setDatasourceId(5L); snapshot.setStatus("ISSUE_FOUND");
        when(snapshotMapper.selectById(80L)).thenReturn(snapshot);
        when(qualityIssueMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.changeStatus(80L, "APPROVED", 9L, "复核"))
                .isInstanceOf(com.dataocean.common.exception.BusinessException.class)
                .hasMessageContaining("高风险问题");

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Wrapper<MetadataQualityIssue>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(qualityIssueMapper).selectCount(wrapper.capture());
        String where = wrapper.getValue().getCustomSqlSegment();
        assertThat(where.toLowerCase()).contains("issue_domain", "severity", "status");
        assertThat(((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper.getValue())
                .getParamNameValuePairs().values().toString())
                .contains("SOURCE", "HIGH", "OPEN", "CONFIRMED");
        verify(snapshotMapper, org.mockito.Mockito.never()).updateById(any(MetadataSnapshot.class));
    }
}
