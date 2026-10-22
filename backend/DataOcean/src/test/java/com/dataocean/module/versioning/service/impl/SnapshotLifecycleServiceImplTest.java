package com.dataocean.module.versioning.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.governance.entity.po.MetadataQualityIssue;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.service.SchemaDiffService;
import com.dataocean.module.user.service.UserService;
import com.dataocean.module.versioning.service.SnapshotAuditLogService;
import com.dataocean.module.versioning.service.SnapshotReleaseGateService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        SnapshotReleaseGateService releaseGateService = mock(SnapshotReleaseGateService.class);
        SchemaDiffService schemaDiffService = mock(SchemaDiffService.class);
        SnapshotAuditLogService auditLogService = mock(SnapshotAuditLogService.class);
        UserService userService = mock(UserService.class);
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        SnapshotLifecycleServiceImpl service = new SnapshotLifecycleServiceImpl(
                snapshotMapper,
                releaseGateService,
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
    void approvalUsesTheSharedReleaseGateAndDoesNotUpdateTheSnapshotOnBlock() {
        MetadataSnapshotMapper snapshotMapper = mock(MetadataSnapshotMapper.class);
        SnapshotReleaseGateService releaseGateService = mock(SnapshotReleaseGateService.class);
        SnapshotLifecycleServiceImpl service = new SnapshotLifecycleServiceImpl(
                snapshotMapper, releaseGateService,
                mock(SchemaDiffService.class), mock(SnapshotAuditLogService.class),
                mock(UserService.class), mock(DatasourceMapper.class));
        MetadataSnapshot snapshot = new MetadataSnapshot();
        snapshot.setId(80L); snapshot.setDatasourceId(5L); snapshot.setStatus("ISSUE_FOUND");
        when(snapshotMapper.selectById(80L)).thenReturn(snapshot);
        doThrow(new BusinessException(409, "无法审核通过：仍有 1 个未解决的高风险问题"))
                .when(releaseGateService).assertApprovable(snapshot);

        assertThatThrownBy(() -> service.changeStatus(80L, "APPROVED", 9L, "复核"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("高风险问题");

        verify(releaseGateService).assertApprovable(snapshot);
        verify(snapshotMapper, never()).updateById(any(MetadataSnapshot.class));
    }

    @Test
    void invalidTargetStatusIsRejectedAsBusinessError() {
        MetadataSnapshotMapper snapshotMapper = mock(MetadataSnapshotMapper.class);
        SnapshotLifecycleServiceImpl service = new SnapshotLifecycleServiceImpl(
                snapshotMapper, mock(SnapshotReleaseGateService.class),
                mock(SchemaDiffService.class), mock(SnapshotAuditLogService.class),
                mock(UserService.class), mock(DatasourceMapper.class));
        MetadataSnapshot snapshot = new MetadataSnapshot();
        snapshot.setId(80L); snapshot.setDatasourceId(5L); snapshot.setStatus("CHECKING");
        when(snapshotMapper.selectById(80L)).thenReturn(snapshot);

        assertThatThrownBy(() -> service.changeStatus(80L, "NOT_A_STATUS", 9L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法快照状态");
    }
}
