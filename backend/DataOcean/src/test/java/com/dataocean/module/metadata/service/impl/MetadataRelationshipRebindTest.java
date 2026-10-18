package com.dataocean.module.metadata.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dataocean.module.metadata.entity.po.MetadataEntity;
import com.dataocean.module.metadata.entity.po.MetadataRelationship;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataRelationshipMapper;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.SchemaSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetadataRelationshipRebindTest {
    @Test
    void confirmedLineageSurvivesEntityCleanupAndRebindsByStableFqn() throws Exception {
        MetadataEntityService entities = mock(MetadataEntityService.class);
        MetadataRelationshipMapper mapper = mock(MetadataRelationshipMapper.class);
        MetadataRelationshipServiceImpl service = service(mapper, entities);
        MetadataEntity oldTable = entity(1L, "sales.orders");
        MetadataRelationship lineage = lineage(100L, 1L, 2L, "sales.orders", "sales.daily_orders");
        when(entities.getByDatasourceId(5L)).thenReturn(List.of(oldTable));
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(lineage));

        service.deleteBySourceDatasource(5L);

        verify(mapper, never()).deleteById(100L);

        MetadataEntity currentSource = entity(51L, "sales.orders");
        MetadataEntity currentTarget = entity(52L, "sales.daily_orders");
        when(entities.getByDatasourceId(5L)).thenReturn(List.of(currentSource, currentTarget));
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(lineage));
        service.rebindConfirmedLineageForSnapshot(5L, 8802L);

        assertThat(lineage.getSourceId()).isEqualTo(51L);
        assertThat(lineage.getTargetId()).isEqualTo(52L);
        Map<String, Object> metadata = new ObjectMapper().readValue(
                lineage.getRelationMetadata(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        assertThat(metadata.get("binding_status")).isEqualTo("BOUND");
        assertThat(metadata.get("bound_snapshot_id")).isEqualTo(8802);
        verify(mapper).updateById(lineage);
    }

    @Test
    void missingEndpointKeepsConfirmedLineageUnboundInsteadOfInventingANewTarget() throws Exception {
        MetadataEntityService entities = mock(MetadataEntityService.class);
        MetadataRelationshipMapper mapper = mock(MetadataRelationshipMapper.class);
        MetadataRelationshipServiceImpl service = service(mapper, entities);
        MetadataRelationship lineage = lineage(100L, 1L, 2L, "sales.orders", "sales.daily_orders");
        when(entities.getByDatasourceId(5L)).thenReturn(List.of(entity(51L, "sales.orders")));
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(lineage));

        service.rebindConfirmedLineageForSnapshot(5L, 8802L);

        assertThat(lineage.getSourceId()).isEqualTo(1L);
        assertThat(lineage.getTargetId()).isEqualTo(2L);
        Map<String, Object> metadata = new ObjectMapper().readValue(
                lineage.getRelationMetadata(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        assertThat(metadata.get("binding_status")).isEqualTo("UNBOUND");
        assertThat(metadata.get("bound_snapshot_id")).isNull();
        verify(mapper).updateById(lineage);
    }

    @Test
    void crossSourceMissingTargetStaysUnboundAndKeepsFailureReason() throws Exception {
        MetadataEntityService entities = mock(MetadataEntityService.class);
        MetadataRelationshipMapper mapper = mock(MetadataRelationshipMapper.class);
        SchemaSnapshotService snapshots = mock(SchemaSnapshotService.class);
        when(snapshots.getPublishedSnapshot(5L)).thenReturn(snapshot(8802L, 5L));
        MetadataRelationshipServiceImpl service = service(mapper, entities, snapshots);
        MetadataRelationship lineage = lineage(100L, 51L, 99L, "sales.orders", "finance.payments");
        when(entities.getByDatasourceId(5L)).thenReturn(List.of(entity(51L, "sales.orders")));
        when(entities.getById(99L)).thenReturn(null);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(lineage));

        service.rebindConfirmedLineageForSnapshot(5L, 8802L);

        Map<String, Object> metadata = metadataOf(lineage);
        assertThat(metadata.get("binding_status")).isEqualTo("UNBOUND");
        assertThat(metadata.get("bound_snapshot_id")).isNull();
        assertThat(metadata.get("source_bound_snapshot_id")).isNull();
        assertThat(metadata.get("target_bound_snapshot_id")).isNull();
        assertThat(String.valueOf(metadata.get("invalid_reason")))
                .contains("TARGET_ENDPOINT_NOT_FOUND");
        @SuppressWarnings("unchecked")
        List<String> unverified = (List<String>) metadata.get("unverified_endpoints");
        assertThat(unverified).contains("TARGET");
        assertThat(metadata.get("confirmation_status")).isEqualTo("CONFIRMED");
        assertThat(lineage.getSourceId()).isEqualTo(51L);
        assertThat(lineage.getTargetId()).isEqualTo(99L);
    }

    @Test
    void crossSourceStaleEndpointStaysUnbound() throws Exception {
        MetadataEntityService entities = mock(MetadataEntityService.class);
        MetadataRelationshipMapper mapper = mock(MetadataRelationshipMapper.class);
        SchemaSnapshotService snapshots = mock(SchemaSnapshotService.class);
        when(snapshots.getPublishedSnapshot(5L)).thenReturn(snapshot(8802L, 5L));
        when(snapshots.getPublishedSnapshot(6L)).thenReturn(snapshot(8803L, 6L));
        MetadataRelationshipServiceImpl service = service(mapper, entities, snapshots);
        MetadataRelationship lineage = lineage(100L, 51L, 99L, "sales.orders", "finance.payments");
        when(entities.getByDatasourceId(5L)).thenReturn(List.of(entity(51L, "sales.orders")));
        when(entities.getById(99L)).thenReturn(entity(99L, "finance.payments", 6L, 7700L));
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(lineage));

        service.rebindConfirmedLineageForSnapshot(5L, 8802L);

        Map<String, Object> metadata = metadataOf(lineage);
        assertThat(metadata.get("binding_status")).isEqualTo("UNBOUND");
        assertThat(String.valueOf(metadata.get("invalid_reason")))
                .contains("TARGET_ENDPOINT_NOT_IN_CURRENT_PUBLISHED_SNAPSHOT");
        assertThat(lineage.getSourceId()).isEqualTo(51L);
        assertThat(lineage.getTargetId()).isEqualTo(99L);
    }

    @Test
    void crossSourceBothEndpointsVerifiedUsesEndpointSpecificSnapshots() throws Exception {
        MetadataEntityService entities = mock(MetadataEntityService.class);
        MetadataRelationshipMapper mapper = mock(MetadataRelationshipMapper.class);
        SchemaSnapshotService snapshots = mock(SchemaSnapshotService.class);
        when(snapshots.getPublishedSnapshot(5L)).thenReturn(snapshot(8802L, 5L));
        when(snapshots.getPublishedSnapshot(6L)).thenReturn(snapshot(7700L, 6L));
        MetadataRelationshipServiceImpl service = service(mapper, entities, snapshots);
        MetadataRelationship lineage = lineage(100L, 51L, 99L, "sales.orders", "finance.payments");
        when(entities.getByDatasourceId(5L)).thenReturn(List.of(entity(51L, "sales.orders")));
        when(entities.getById(99L)).thenReturn(entity(99L, "finance.payments", 6L, 7700L));
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(lineage));

        service.rebindConfirmedLineageForSnapshot(5L, 8802L);

        Map<String, Object> metadata = metadataOf(lineage);
        assertThat(metadata.get("binding_status")).isEqualTo("BOUND");
        assertThat(metadata.get("source_bound_snapshot_id")).isEqualTo(8802);
        assertThat(metadata.get("target_bound_snapshot_id")).isEqualTo(7700);
        assertThat(metadata.get("bound_snapshot_id")).isNull();
        assertThat(metadata.get("confirmation_status")).isEqualTo("CONFIRMED");
        assertThat(lineage.getSourceId()).isEqualTo(51L);
        assertThat(lineage.getTargetId()).isEqualTo(99L);
    }

    private static MetadataRelationshipServiceImpl service(MetadataRelationshipMapper mapper,
                                                           MetadataEntityService entities) {
        SchemaSnapshotService snapshots = mock(SchemaSnapshotService.class);
        when(snapshots.getPublishedSnapshot(5L)).thenReturn(snapshot(8802L, 5L));
        return service(mapper, entities, snapshots);
    }

    private static MetadataRelationshipServiceImpl service(MetadataRelationshipMapper mapper,
                                                           MetadataEntityService entities,
                                                           SchemaSnapshotService snapshots) {
        MetadataRelationshipServiceImpl service =
                new MetadataRelationshipServiceImpl(entities, snapshots, new ObjectMapper());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        return service;
    }

    private static MetadataSnapshot snapshot(Long id, Long datasourceId) {
        MetadataSnapshot snapshot = new MetadataSnapshot();
        snapshot.setId(id);
        snapshot.setDatasourceId(datasourceId);
        snapshot.setStatus(MetadataSnapshot.STATUS_PUBLISHED);
        return snapshot;
    }

    private static MetadataEntity entity(Long id, String fqn) {
        return entity(id, fqn, 5L, 8802L);
    }

    private static MetadataEntity entity(Long id, String fqn, Long datasourceId, Long snapshotId) {
        MetadataEntity entity = new MetadataEntity();
        entity.setId(id);
        entity.setFqn(fqn);
        entity.setEntityType(MetadataEntity.TYPE_TABLE);
        entity.setEntityMetadata("{\"datasource_id\":" + datasourceId
                + ",\"snapshot_id\":" + snapshotId + "}");
        return entity;
    }

    private static Map<String, Object> metadataOf(MetadataRelationship relationship) throws Exception {
        return new ObjectMapper().readValue(relationship.getRelationMetadata(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    private static MetadataRelationship lineage(Long id, Long sourceId, Long targetId,
                                                String sourceFqn, String targetFqn) {
        MetadataRelationship relation = new MetadataRelationship();
        relation.setId(id);
        relation.setSourceId(sourceId);
        relation.setSourceType(MetadataEntity.TYPE_TABLE);
        relation.setTargetId(targetId);
        relation.setTargetType(MetadataEntity.TYPE_TABLE);
        relation.setRelationType(MetadataRelationship.TYPE_LINEAGE);
        relation.setRelationMetadata("{\"source_fqn\":\"" + sourceFqn
                + "\",\"target_fqn\":\"" + targetFqn
                + "\",\"confirmation_status\":\"CONFIRMED\",\"binding_status\":\"BOUND\"}");
        return relation;
    }
}
