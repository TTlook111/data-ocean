package com.dataocean.module.versioning.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.versioning.event.SnapshotPublishedEvent;
import com.dataocean.module.versioning.service.SnapshotAuditLogService;
import com.dataocean.module.versioning.service.SnapshotReleaseGateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnapshotPublishServiceImplTest {

    private MetadataSnapshotMapper snapshotMapper;
    private SnapshotAuditLogService auditLogService;
    private SnapshotReleaseGateService releaseGateService;
    private ApplicationEventPublisher eventPublisher;
    private SnapshotPublishServiceImpl service;

    @BeforeEach
    void setUp() {
        snapshotMapper = mock(MetadataSnapshotMapper.class);
        auditLogService = mock(SnapshotAuditLogService.class);
        releaseGateService = mock(SnapshotReleaseGateService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new SnapshotPublishServiceImpl(
                snapshotMapper, auditLogService, releaseGateService, eventPublisher);
    }

    @Test
    void nonApprovedSnapshotIsRejectedBeforeGate() {
        MetadataSnapshot snapshot = snapshot(80L, "ISSUE_FOUND");
        when(snapshotMapper.selectById(80L)).thenReturn(snapshot);

        assertThatThrownBy(() -> service.publishSnapshot(80L, 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("APPROVED");

        verify(releaseGateService, never()).assertPublishable(any());
        verify(snapshotMapper, never()).updateById(any(MetadataSnapshot.class));
    }

    @Test
    void blockedGateLeavesOldAndNewSnapshotUntouchedAndPublishesNoEvent() {
        MetadataSnapshot snapshot = snapshot(80L, MetadataSnapshot.STATUS_APPROVED);
        when(snapshotMapper.selectById(80L)).thenReturn(snapshot);
        doThrow(new BusinessException(409, "无法发布：存在 1 个未解决的高风险问题"))
                .when(releaseGateService).assertPublishable(snapshot);

        assertThatThrownBy(() -> service.publishSnapshot(80L, 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("高风险问题");

        verify(snapshotMapper, never()).selectList(any(Wrapper.class));
        verify(snapshotMapper, never()).updateById(any(MetadataSnapshot.class));
        verify(eventPublisher, never()).publishEvent(any());
        verify(auditLogService, never()).recordStatusChange(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void gateRunsAfterStatusCheckAndBeforeExpiringThePreviousVersion() {
        MetadataSnapshot snapshot = snapshot(80L, MetadataSnapshot.STATUS_APPROVED);
        MetadataSnapshot previous = snapshot(70L, MetadataSnapshot.STATUS_PUBLISHED);
        when(snapshotMapper.selectById(80L)).thenReturn(snapshot);
        when(snapshotMapper.selectList(any(Wrapper.class))).thenReturn(List.of(previous));

        service.publishSnapshot(80L, 9L);

        InOrder order = inOrder(snapshotMapper, releaseGateService);
        order.verify(snapshotMapper).selectById(80L);
        order.verify(releaseGateService).assertPublishable(snapshot);
        order.verify(snapshotMapper).selectList(any(Wrapper.class));

        verify(snapshotMapper, org.mockito.Mockito.times(2)).updateById(any(MetadataSnapshot.class));
        verify(eventPublisher).publishEvent(any(SnapshotPublishedEvent.class));
    }

    private MetadataSnapshot snapshot(Long id, String status) {
        MetadataSnapshot snapshot = new MetadataSnapshot();
        snapshot.setId(id);
        snapshot.setDatasourceId(5L);
        snapshot.setStatus(status);
        return snapshot;
    }
}
