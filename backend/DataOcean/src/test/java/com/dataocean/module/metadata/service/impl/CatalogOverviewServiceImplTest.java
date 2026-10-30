package com.dataocean.module.metadata.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataocean.module.datasource.entity.po.Datasource;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.fieldtag.service.FieldTagService;
import com.dataocean.module.glossary.mapper.GlossaryTermMapper;
import com.dataocean.module.glossary.service.GlossaryScopeService;
import com.dataocean.module.glossary.service.GlossaryTermWorkflowService;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import com.dataocean.module.metadata.entity.po.MetadataEntity;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.entity.vo.CatalogEntityOverviewVO;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataRelationshipMapper;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import com.dataocean.module.metadata.service.SchemaSnapshotService;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 详情聚合的来源快照约束：一次读取只能使用一个可核验的来源快照。
 */
class CatalogOverviewServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 需要 TableInfo 才能生成 SQL 片段和绑定参数。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), DbColumnMeta.class);
    }

    private final MetadataEntityService entityService = mock(MetadataEntityService.class);
    private final MetadataRelationshipService relationshipService = mock(MetadataRelationshipService.class);
    private final MetadataRelationshipMapper relationshipMapper = mock(MetadataRelationshipMapper.class);
    private final SchemaSnapshotService snapshotService = mock(SchemaSnapshotService.class);
    private final DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
    private final DbTableMetaMapper tableMetaMapper = mock(DbTableMetaMapper.class);
    private final DbColumnMetaMapper columnMetaMapper = mock(DbColumnMetaMapper.class);
    private final GlossaryTermMapper glossaryTermMapper = mock(GlossaryTermMapper.class);
    private final GlossaryScopeService glossaryScopeService = mock(GlossaryScopeService.class);
    private final GlossaryTermWorkflowService glossaryTermWorkflowService = mock(GlossaryTermWorkflowService.class);
    private final FieldTagService fieldTagService = mock(FieldTagService.class);
    private final IamS1AdminGuard adminGuard = mock(IamS1AdminGuard.class);
    private final IamS1CapabilityService capabilityService = mock(IamS1CapabilityService.class);

    private final CatalogOverviewServiceImpl service = new CatalogOverviewServiceImpl(
            entityService, relationshipService, relationshipMapper, snapshotService, datasourceMapper,
            tableMetaMapper, columnMetaMapper, glossaryTermMapper, glossaryScopeService,
            glossaryTermWorkflowService, fieldTagService, adminGuard, capabilityService, new ObjectMapper());

    @BeforeEach
    void setUpDefaults() {
        when(capabilityService.responsibleDatasourcesWithFunction(anyLong(), anyString()))
                .thenReturn(List.of(new IamS1DatasourceRefVO(1L, "ds", true)));
        when(entityService.getEntityKeysByDatasourceIds(any())).thenReturn(Set.of());
        when(relationshipService.getVisibleRelationsByType(anyString(), anyLong(), any())).thenReturn(List.of());
        when(relationshipService.getBySource(anyLong(), anyString())).thenReturn(List.of());
        when(relationshipService.getByTarget(anyLong(), anyString())).thenReturn(List.of());
        when(relationshipService.getDownstream(anyString(), anyLong(), anyInt(), any())).thenReturn(List.of());
        when(relationshipMapper.selectList(any())).thenReturn(List.of());
        when(relationshipMapper.selectByTargetAndRelationType(anyLong(), anyString(), anyString()))
                .thenReturn(List.of());
        when(glossaryScopeService.visibleDatasourceIds(anyLong(), anyString())).thenReturn(Set.of(1L));
    }

    @Test
    void detailUsesEntitySourceSnapshotAndDoesNotMixPublishedMetadata() {
        MetadataEntity table = entity(100L, MetadataEntity.TYPE_TABLE, "ds.db.orders", "orders",
                "{\"datasource_id\":1,\"snapshot_id\":1001,\"table_name\":\"orders\","
                        + "\"table_meta_id\":11,\"governance_status\":\"NORMAL\"}");
        MetadataEntity columnEntity = entity(101L, MetadataEntity.TYPE_COLUMN,
                "ds.db.orders.amount", "amount",
                "{\"datasource_id\":1,\"snapshot_id\":1001,\"table_name\":\"orders\","
                        + "\"column_name\":\"amount\"}");
        MetadataSnapshot source = snapshot(1001L, 1, MetadataSnapshot.STATUS_PUBLISHED);
        MetadataSnapshot published = snapshot(2002L, 2, MetadataSnapshot.STATUS_PUBLISHED);
        published.setEntitySyncStatus(MetadataSnapshot.ENTITY_SYNC_FAILED);
        published.setEntitySyncError("sync failed");
        DbTableMeta tableMeta = tableMeta(11L, 1L, 1001L, "orders", "旧表注释");
        DbColumnMeta columnMeta = columnMeta(21L, 1L, 1001L, "orders", "amount",
                "DECIMAL(10,2)", "旧字段注释");

        when(entityService.getById(100L)).thenReturn(table);
        when(entityService.getById(101L)).thenReturn(columnEntity);
        when(entityService.getDatasourceIdByEntityId(100L)).thenReturn(1L);
        when(entityService.getByDatasourceId(1L)).thenReturn(List.of(table, columnEntity));
        when(entityService.getEntityKeysByDatasourceIds(any()))
                .thenReturn(Set.of("TABLE#100", "COLUMN#101"));
        when(datasourceMapper.selectById(1L)).thenReturn(datasource());
        when(snapshotService.getPublishedSnapshot(1L)).thenReturn(published);
        when(snapshotService.getById(1001L)).thenReturn(source);
        when(tableMetaMapper.selectById(11L)).thenReturn(tableMeta);
        when(columnMetaMapper.selectList(any())).thenAnswer(invocation -> {
            Object wrapper = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            AbstractWrapper<?, ?, ?> query = (AbstractWrapper<?, ?, ?>) wrapper;
            // MyBatis-Plus 懒构建条件片段；先取 SQL 片段，再核对实际绑定参数。
            query.getSqlSegment();
            Map<String, Object> params = query.getParamNameValuePairs();
            List<String> values = params.values().stream().map(String::valueOf).toList();
            assertThat(values).contains("1001").doesNotContain("2002");
            return List.of(columnMeta);
        });

        CatalogEntityOverviewVO overview = service.overview(7L, 100L);

        assertThat(overview.sourceSnapshot().id()).isEqualTo(1001L);
        assertThat(overview.sourceSnapshot().version()).isEqualTo(1);
        assertThat(overview.publishedSnapshot().id()).isEqualTo(2002L);
        assertThat(overview.publishedSnapshot().version()).isEqualTo(2);
        assertThat(overview.sourceMatchesPublished()).isFalse();
        assertThat(overview.sourceVerified()).isTrue();
        assertThat(overview.sourceMessage()).contains("v1").contains("v2");
        assertThat(overview.table().comment()).isEqualTo("旧表注释");
        assertThat(overview.columns().status()).isEqualTo("OK");
        assertThat(overview.columns().items()).hasSize(1);
        assertThat(overview.columns().items().get(0).dataType()).isEqualTo("DECIMAL(10,2)");
        assertThat(overview.columns().items().get(0).comment()).isEqualTo("旧字段注释");
        assertThat(overview.columns().items().get(0).entityId()).isEqualTo(101L);
        assertThat(overview.counts().columns()).isEqualTo(1);
        assertThat(overview.terms().status()).isEqualTo("OK");
        assertThat(overview.tags().status()).isEqualTo("OK");
        assertThat(overview.lineage().status()).isEqualTo("OK");
        assertThat(overview.downstream().status()).isEqualTo("OK");
    }

    @Test
    void unverifiableSourceDoesNotMasqueradeAsNormalEmptySections() {
        MetadataEntity table = entity(100L, MetadataEntity.TYPE_TABLE, "ds.db.orders", "orders",
                "{\"datasource_id\":1,\"snapshot_id\":9999,\"table_name\":\"orders\"}");
        MetadataSnapshot published = snapshot(2002L, 2, MetadataSnapshot.STATUS_PUBLISHED);

        when(entityService.getById(100L)).thenReturn(table);
        when(entityService.getDatasourceIdByEntityId(100L)).thenReturn(1L);
        when(datasourceMapper.selectById(1L)).thenReturn(datasource());
        when(snapshotService.getPublishedSnapshot(1L)).thenReturn(published);
        when(snapshotService.getById(9999L)).thenReturn(null);
        when(capabilityService.responsibleDatasourcesWithFunction(anyLong(), anyString()))
                .thenReturn(List.of());

        CatalogEntityOverviewVO overview = service.overview(7L, 100L);

        assertThat(overview.sourceSnapshot()).isNull();
        assertThat(overview.publishedSnapshot().id()).isEqualTo(2002L);
        assertThat(overview.sourceVerified()).isFalse();
        assertThat(overview.sourceMatchesPublished()).isFalse();
        assertThat(overview.sourceMessage()).isNotBlank();
        assertThat(overview.table().tableName()).isEqualTo("orders");
        assertThat(overview.column()).isNull();
        assertThat(overview.columns().status()).isEqualTo("UNAVAILABLE");
        assertThat(overview.terms().status()).isEqualTo("UNAVAILABLE");
        assertThat(overview.tags().status()).isEqualTo("UNAVAILABLE");
        assertThat(overview.fieldTags().status()).isEqualTo("UNAVAILABLE");
        assertThat(overview.structureRelations().status()).isEqualTo("UNAVAILABLE");
        assertThat(overview.lineage().status()).isEqualTo("UNAVAILABLE");
        assertThat(overview.downstream().status()).isEqualTo("UNAVAILABLE");
        assertThat(overview.counts().columns()).isZero();
        assertThat(overview.counts().terms()).isZero();
        assertThat(overview.counts().tags()).isZero();
        assertThat(overview.counts().lineage()).isZero();
        assertThat(overview.counts().downstream()).isZero();
        verifyNoInteractions(tableMetaMapper, columnMetaMapper, relationshipMapper, relationshipService,
                glossaryTermMapper, glossaryScopeService, glossaryTermWorkflowService, fieldTagService,
                adminGuard);
    }

    private static MetadataEntity entity(Long id, String type, String fqn, String name, String metadata) {
        MetadataEntity entity = new MetadataEntity();
        entity.setId(id);
        entity.setEntityType(type);
        entity.setFqn(fqn);
        entity.setName(name);
        entity.setDisplayName(name);
        entity.setEntityMetadata(metadata);
        return entity;
    }

    private static MetadataSnapshot snapshot(Long id, int version, String status) {
        MetadataSnapshot snapshot = new MetadataSnapshot();
        snapshot.setId(id);
        snapshot.setDatasourceId(1L);
        snapshot.setSnapshotVersion(version);
        snapshot.setStatus(status);
        return snapshot;
    }

    private static Datasource datasource() {
        Datasource datasource = new Datasource();
        datasource.setId(1L);
        datasource.setName("ds");
        datasource.setDatabaseName("db");
        return datasource;
    }

    private static DbTableMeta tableMeta(Long id, Long datasourceId, Long snapshotId,
                                         String tableName, String comment) {
        DbTableMeta meta = new DbTableMeta();
        meta.setId(id);
        meta.setDatasourceId(datasourceId);
        meta.setSnapshotId(snapshotId);
        meta.setTableName(tableName);
        meta.setTableComment(comment);
        meta.setTableType("TABLE");
        meta.setGovernanceStatus("NORMAL");
        return meta;
    }

    private static DbColumnMeta columnMeta(Long id, Long datasourceId, Long snapshotId,
                                           String tableName, String columnName,
                                           String dataType, String comment) {
        DbColumnMeta meta = new DbColumnMeta();
        meta.setId(id);
        meta.setDatasourceId(datasourceId);
        meta.setSnapshotId(snapshotId);
        meta.setTableName(tableName);
        meta.setColumnName(columnName);
        meta.setDataType(dataType);
        meta.setColumnComment(comment);
        meta.setIsNullable(1);
        meta.setIsPrimaryKey(0);
        meta.setGovernanceStatus("NORMAL");
        meta.setOrdinalPosition(1);
        return meta;
    }
}
