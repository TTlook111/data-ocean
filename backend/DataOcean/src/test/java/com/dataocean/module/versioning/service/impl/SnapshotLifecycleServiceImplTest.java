package com.dataocean.module.versioning.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
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
}
