package com.dataocean.module.metadata.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.mapper.MetadataRelationshipMapper;
import com.dataocean.module.metadata.service.MetadataEntityService;
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

    private static MetadataRelationshipServiceImpl service(MetadataRelationshipMapper mapper,
                                                           MetadataEntityService entities) {
        MetadataRelationshipServiceImpl service =
                new MetadataRelationshipServiceImpl(entities, new ObjectMapper());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        return service;
    }

    private static MetadataEntity entity(Long id, String fqn) {
        MetadataEntity entity = new MetadataEntity();
        entity.setId(id);
        entity.setFqn(fqn);
        entity.setEntityType(MetadataEntity.TYPE_TABLE);
        return entity;
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
