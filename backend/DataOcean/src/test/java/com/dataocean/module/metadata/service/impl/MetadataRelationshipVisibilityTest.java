package com.dataocean.module.metadata.service.impl;

import com.dataocean.module.metadata.entity.po.MetadataEntity;
import com.dataocean.module.metadata.entity.po.MetadataRelationship;
import com.dataocean.module.metadata.mapper.MetadataRelationshipMapper;
import com.dataocean.module.metadata.service.MetadataEntityService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 3 聚焦测试：血缘图的可见性裁剪。
 *
 * <p>血缘是跨数据源的图。只校验起点会让关系另一侧属于无权数据源的元数据一并返回；
 * 而“先完整遍历、返回前再过滤”又会留下与起点没有边的孤立节点，
 * 泄露“存在一条隐藏路径”的拓扑信号。</p>
 */
class MetadataRelationshipVisibilityTest {

    @Test
    void lineageDropsEdgesWhoseOtherSideIsNotVisible() {
        Fixture fixture = new Fixture();
        // 1 → 2 两端可见；1 → 99 的另一端不可见。
        when(fixture.mapper.selectLineageByEntityKey(1L, MetadataEntity.TYPE_TABLE)).thenReturn(List.of(
                edge(10L, 1L, 2L), edge(11L, 1L, 99L)));

        List<MetadataRelationship> visible = fixture.service.getLineage(1L, List.of(5L));

        assertThat(visible).extracting(MetadataRelationship::getId).containsExactly(10L);
    }

    @Test
    void downstreamStopsAtInvisibleNodeInsteadOfBridgingThroughIt() {
        Fixture fixture = new Fixture();
        // 图：1（可见）→ 2（无权）→ 3（可见）。
        // 只过滤返回结果会返回一个孤立的 3；正确行为是不返回 2→3 这条边，也不再继续。
        // 所以可见集合里刻意不含实体 2。
        when(fixture.entityService.getEntityKeysByDatasourceIds(any()))
                .thenReturn(Set.of("TABLE#1", "TABLE#3"));
        when(fixture.mapper.selectBySourceAndRelationType(
                eq(1L), eq(MetadataEntity.TYPE_TABLE), eq(MetadataRelationship.TYPE_LINEAGE)))
                .thenReturn(List.of(lineageEdge(1L, 2L)));
        when(fixture.mapper.selectBySourceAndRelationType(
                eq(2L), eq(MetadataEntity.TYPE_TABLE), eq(MetadataRelationship.TYPE_LINEAGE)))
                .thenReturn(List.of(lineageEdge(2L, 3L)));
        when(fixture.mapper.selectBySourceAndRelationType(
                eq(3L), eq(MetadataEntity.TYPE_TABLE), eq(MetadataRelationship.TYPE_LINEAGE)))
                .thenReturn(List.of());

        List<MetadataRelationship> result = fixture.service.getDownstream(1L, 5, List.of(5L));

        assertThat(result).isEmpty();
    }

    @Test
    void downstreamReturnsEdgesWithinVisibleScope() {
        Fixture fixture = new Fixture();
        when(fixture.mapper.selectBySourceAndRelationType(
                eq(1L), eq(MetadataEntity.TYPE_TABLE), eq(MetadataRelationship.TYPE_LINEAGE)))
                .thenReturn(List.of(lineageEdge(1L, 2L)));
        when(fixture.mapper.selectBySourceAndRelationType(
                eq(2L), eq(MetadataEntity.TYPE_TABLE), eq(MetadataRelationship.TYPE_LINEAGE)))
                .thenReturn(List.of());

        List<MetadataRelationship> result = fixture.service.getDownstream(1L, 5, List.of(5L));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTargetId()).isEqualTo(2L);
    }

    @Test
    void sameNumericIdWithDifferentTypeDoesNotMakeEndpointVisible() {
        Fixture fixture = new Fixture();
        // 可见集合里有 COLUMN#2，但没有 TABLE#2；边的目标端是 TABLE#2。
        when(fixture.entityService.getEntityKeysByDatasourceIds(any()))
                .thenReturn(Set.of("TABLE#1", "COLUMN#2"));
        when(fixture.mapper.selectBySourceAndRelationType(
                eq(1L), eq(MetadataEntity.TYPE_TABLE), eq(MetadataRelationship.TYPE_LINEAGE)))
                .thenReturn(List.of(lineageEdge(1L, 2L)));

        List<MetadataRelationship> result = fixture.service.getDownstream(1L, 5, List.of(5L));

        assertThat(result).isEmpty();
    }

    @Test
    void unboundLineageIsNotUsedAsTraversalBridge() {
        Fixture fixture = new Fixture();
        MetadataRelationship edge = lineageEdge(1L, 2L);
        edge.setRelationMetadata("{\"binding_status\":\"UNBOUND\"}");
        when(fixture.mapper.selectBySourceAndRelationType(
                eq(1L), eq(MetadataEntity.TYPE_TABLE), eq(MetadataRelationship.TYPE_LINEAGE)))
                .thenReturn(List.of(edge));

        List<MetadataRelationship> result = fixture.service.getDownstream(1L, 5, List.of(5L));

        assertThat(result).isEmpty();
    }

    @Test
    void upsertDeduplicatesByEndpointTypesNotOnlyNumericIds() {
        Fixture fixture = new Fixture();
        MetadataRelationship relationship = edge(100L, 1L, 2L);
        relationship.setRelationType(MetadataRelationship.TYPE_TAGGED_WITH);
        when(fixture.mapper.selectBetweenTyped(1L, MetadataEntity.TYPE_TABLE,
                2L, MetadataEntity.TYPE_TABLE, MetadataRelationship.TYPE_TAGGED_WITH))
                .thenReturn(null);

        fixture.service.upsert(relationship);

        verify(fixture.mapper).selectBetweenTyped(1L, MetadataEntity.TYPE_TABLE,
                2L, MetadataEntity.TYPE_TABLE, MetadataRelationship.TYPE_TAGGED_WITH);
        verify(fixture.mapper, never()).selectBetween(any(), any(), any());
        verify(fixture.mapper).insert(relationship);
    }

    private static MetadataRelationship edge(Long id, Long source, Long target) {
        MetadataRelationship rel = new MetadataRelationship();
        rel.setId(id);
        rel.setSourceId(source);
        rel.setSourceType(MetadataEntity.TYPE_TABLE);
        rel.setTargetId(target);
        rel.setTargetType(MetadataEntity.TYPE_TABLE);
        return rel;
    }

    private static MetadataRelationship lineageEdge(Long source, Long target) {
        MetadataRelationship rel = edge(source * 1000 + target, source, target);
        rel.setRelationType(MetadataRelationship.TYPE_LINEAGE);
        return rel;
    }

    private static final class Fixture {
        private final MetadataRelationshipMapper mapper = mock(MetadataRelationshipMapper.class);
        private final MetadataEntityService entityService = mock(MetadataEntityService.class);
        // ServiceImpl 的 baseMapper 由容器注入，单元测试里要手动塞进去。
        private final MetadataRelationshipServiceImpl service = newService(mapper, entityService);

        private static MetadataRelationshipServiceImpl newService(MetadataRelationshipMapper mapper,
                                                                  MetadataEntityService entityService) {
            MetadataRelationshipServiceImpl impl = new MetadataRelationshipServiceImpl(entityService,
                    mock(com.dataocean.module.metadata.service.SchemaSnapshotService.class),
                    new com.fasterxml.jackson.databind.ObjectMapper());
            org.springframework.test.util.ReflectionTestUtils.setField(impl, "baseMapper", mapper);
            return impl;
        }

        {
            MetadataEntity table = new MetadataEntity();
            table.setId(1L);
            table.setEntityType(MetadataEntity.TYPE_TABLE);
            when(entityService.getById(1L)).thenReturn(table);
            // 可见源 5 只包含实体 1、2、3（按端点类型 + ID 表示）
            when(entityService.getEntityKeysByDatasourceIds(any()))
                    .thenReturn(Set.of("TABLE#1", "TABLE#2", "TABLE#3"));
        }
    }
}
