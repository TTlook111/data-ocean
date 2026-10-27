package com.dataocean.module.knowledge.service.impl;

import com.dataocean.module.metadata.entity.po.*;
import com.dataocean.module.metadata.mapper.*;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class LineageFactServiceTest {
    @Test void generationNeverPairsAnOldBodyWithANewerSourceFingerprint() throws Exception {
        Fixture f = new Fixture();
        f.metadata.put("description", "旧转换口径");
        MetadataRelationship stale = new MetadataRelationship();
        stale.setId(10L); stale.setSourceId(1L); stale.setTargetId(2L);
        stale.setRelationType("LINEAGE"); stale.setSourceType("TABLE"); stale.setTargetType("TABLE");
        stale.setRelationMetadata(f.json.writeValueAsString(f.metadata));
        // 模拟列表读取后、精确来源读取前，同一关系已经被另一事务修改。
        f.metadata.put("description", "新转换口径"); f.refresh();
        var scoped = List.of(f.entities.getById(1L), f.entities.getById(2L));
        when(f.entities.getByDatasourceId(5L)).thenReturn(scoped);
        var rows = mock(MetadataRelationshipMapper.class);
        when(rows.selectByEntityIds(any())).thenReturn(List.of(stale));
        var publisher = mock(KnowledgeDocPublishService.class, CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(publisher, "metadataEntityService", f.entities);
        org.springframework.test.util.ReflectionTestUtils.setField(publisher, "metadataRelationshipMapper", rows);
        org.springframework.test.util.ReflectionTestUtils.setField(publisher, "lineageFacts", f.service);
        List<Map<String, Object>> facts = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                publisher, "loadConfirmedLineage", 5L, 88L);
        assertThat(facts).hasSize(1);
        var fact = facts.get(0);
        assertThat(fact.get("description")).isEqualTo("新转换口径");
        assertThat(fact.get("source_fingerprint")).isEqualTo(f.service.currentReference(5L, 88L, 10L).get("sourceFingerprint"));
        f.metadata.put("description", "再次修改"); f.refresh();
        assertThat(f.service.valid(5L, 88L, "LINEAGE", List.of("10"),
                (String) fact.get("source_fingerprint"), (List<String>) fact.get("dependencies"))).isFalse();
    }
    @Test void fieldDerivationRequiresItsRealColumnsAndRejectsBlockedDependencies() throws Exception {
        Fixture f = new Fixture();
        f.relation.setRelationType("DERIVED_FROM"); f.relation.setSourceType("COLUMN"); f.relation.setTargetType("COLUMN");
        f.entities.getById(1L).setEntityType("COLUMN"); f.entities.getById(1L).setFqn("ds.db.orders.id");
        f.entities.getById(2L).setEntityType("COLUMN"); f.entities.getById(2L).setFqn("ds.db.rollups.total");
        f.metadata.put("source_fqn", "ds.db.orders.id"); f.metadata.put("target_fqn", "ds.db.rollups.total"); f.refresh();
        DbColumnMeta column = new DbColumnMeta(); column.setGovernanceStatus("NORMAL");
        when(f.columns.selectOne(any())).thenReturn(column);
        var ref = f.service.currentReference(5L, 88L, 10L);
        assertThat((List<?>) ref.get("resourceDependencies")).hasSize(4);
        String frozen = f.json.writeValueAsString(List.of(ref));
        assertThat(f.service.referencesValid(5L, 88L, frozen)).isTrue();
        column.setGovernanceStatus("BLOCKED");
        assertThat(f.service.referencesValid(5L, 88L, frozen)).isFalse();
    }
    @Test void sameIdContentChangeRevocationAndRebindingInvalidateFrozenReference() throws Exception {
        Fixture f = new Fixture();
        var ref = f.service.currentReference(5L, 88L, 10L);
        assertThat(ref.get("resourceDependencies")).isEqualTo(List.of("table:orders", "table:rollups"));
        String frozen = f.json.writeValueAsString(List.of(ref));
        assertThat(f.service.referencesValid(5L, 88L, frozen)).isTrue();
        f.metadata.put("description", "新的转换口径"); f.refresh();
        assertThat(f.service.referencesValid(5L, 88L, frozen)).isFalse();
        f.metadata.remove("description"); f.metadata.put("confirmation_status", "PENDING"); f.refresh();
        assertThat(f.service.referencesValid(5L, 88L, frozen)).isFalse();
        f.metadata.put("confirmation_status", "CONFIRMED"); f.metadata.put("target_bound_snapshot_id", 89); f.refresh();
        assertThat(f.service.referencesValid(5L, 88L, frozen)).isFalse();
    }

    @Test void hiddenOrExternalEndpointAndWrongEntityKindAreRejected() throws Exception {
        Fixture f = new Fixture();
        when(f.entities.getDatasourceIdByEntityId(2L)).thenReturn(6L);
        assertThatThrownBy(() -> f.service.currentReference(5L, 88L, 10L)).hasMessageContaining("血缘事实");
        when(f.entities.getDatasourceIdByEntityId(2L)).thenReturn(5L);
        f.relation.setRelationType("DERIVED_FROM");
        assertThatThrownBy(() -> f.service.currentReference(5L, 88L, 10L)).hasMessageContaining("血缘事实");
    }

    private static class Fixture {
        final ObjectMapper json = new ObjectMapper();
        final MetadataRelationshipMapper relations = mock(MetadataRelationshipMapper.class);
        final MetadataEntityService entities = mock(MetadataEntityService.class);
        final MetadataSnapshotMapper snapshots = mock(MetadataSnapshotMapper.class);
        final DbTableMetaMapper tables = mock(DbTableMetaMapper.class);
        final DbColumnMetaMapper columns = mock(DbColumnMetaMapper.class);
        final LineageFactService service = new LineageFactService(relations, entities, snapshots, tables, columns, json);
        final MetadataRelationship relation = new MetadataRelationship();
        final Map<String, Object> metadata = new LinkedHashMap<>();
        Fixture() throws Exception {
            MetadataSnapshot snapshot = new MetadataSnapshot(); snapshot.setId(88L); snapshot.setDatasourceId(5L);
            when(snapshots.selectById(88L)).thenReturn(snapshot); when(snapshots.selectOne(any())).thenReturn(snapshot);
            relation.setId(10L); relation.setSourceId(1L); relation.setTargetId(2L);
            relation.setSourceType("TABLE"); relation.setTargetType("TABLE"); relation.setRelationType("LINEAGE");
            metadata.put("confirmation_status", "CONFIRMED"); metadata.put("binding_status", "BOUND");
            metadata.put("source_bound_snapshot_id", 88); metadata.put("target_bound_snapshot_id", 88);
            metadata.put("source_fqn", "ds.db.orders"); metadata.put("target_fqn", "ds.db.rollups"); refresh();
            when(relations.selectById(10L)).thenReturn(relation);
            for (long id : List.of(1L, 2L)) {
                MetadataEntity entity = new MetadataEntity(); entity.setId(id); entity.setEntityType("TABLE");
                entity.setFqn(id == 1 ? "ds.db.orders" : "ds.db.rollups");
                when(entities.getById(id)).thenReturn(entity); when(entities.getDatasourceIdByEntityId(id)).thenReturn(5L);
            }
            DbTableMeta table = new DbTableMeta(); table.setId(20L); table.setGovernanceStatus("NORMAL");
            when(tables.selectOne(any())).thenReturn(table);
        }
        void refresh() throws Exception { relation.setRelationMetadata(json.writeValueAsString(metadata)); }
    }
}
