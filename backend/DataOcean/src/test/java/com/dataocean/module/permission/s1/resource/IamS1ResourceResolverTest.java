package com.dataocean.module.permission.s1.resource;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataEntityMapper;
import com.dataocean.module.metadata.mapper.MetadataRelationshipMapper;
import com.dataocean.module.permission.s1.resource.impl.LineageRelationshipResourceResolver;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.fieldtag.entity.UserFeedback;
import com.dataocean.module.fieldtag.mapper.UserFeedbackMapper;
import com.dataocean.module.permission.s1.entity.IamS1DatasourceFact;
import com.dataocean.module.permission.s1.mapper.IamS1DatasourceIdentityMapper;
import com.dataocean.module.permission.s1.resource.impl.DatasourceResourceResolver;
import com.dataocean.module.permission.s1.resource.impl.FeedbackReviewResourceResolver;
import com.dataocean.module.permission.s1.resource.impl.MetadataColumnResourceResolver;
import com.dataocean.module.permission.s1.resource.impl.MetadataEntityResourceResolver;
import com.dataocean.module.permission.s1.resource.impl.SnapshotResourceResolver;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 资源解析器：把资源 ID 还原成真实归属，并防止参数欺骗。
 *
 * <p>解析器**不接受**调用方自带的 datasourceId；归属只从真实资源事实读取。
 * 资源不存在、归属断链、类型不符时一律 fail-closed。</p>
 */
class IamS1ResourceResolverTest {

    @Test
    void datasourceResolverReturnsItsOwnIdentity() {
        IamS1DatasourceIdentityMapper mapper = mock(IamS1DatasourceIdentityMapper.class);
        IamS1DatasourceFact fact = new IamS1DatasourceFact();
        fact.setId(5L);
        fact.setStatus(1);
        when(mapper.selectIdentity(5L)).thenReturn(fact);
        DatasourceResourceResolver resolver = new DatasourceResourceResolver(mapper);

        IamS1ResolvedResource resolved = resolver.resolve(5L);

        assertThat(resolved.datasourceId()).isEqualTo(5L);
        assertThat(resolved.hasDatasource()).isTrue();
    }

    @Test
    void datasourceResolverRejectsMissingOrDeletedDatasource() {
        IamS1DatasourceIdentityMapper mapper = mock(IamS1DatasourceIdentityMapper.class);
        DatasourceResourceResolver resolver = new DatasourceResourceResolver(mapper);

        assertThat(catchThrowable(() -> resolver.resolve(null))).isInstanceOf(BusinessException.class);
        assertThat(catchThrowable(() -> resolver.resolve("not-a-number")))
                .isInstanceOf(BusinessException.class);
        // 不存在 / 已删除：mapper 返回 null
        assertThat(catchThrowable(() -> resolver.resolve(404L))).isInstanceOf(BusinessException.class);
    }

    @Test
    void snapshotResolverResolvesRealDatasource() {
        MetadataSnapshotMapper mapper = mock(MetadataSnapshotMapper.class);
        MetadataSnapshot snapshot = new MetadataSnapshot();
        snapshot.setId(8L);
        snapshot.setDatasourceId(5L);
        when(mapper.selectById(8L)).thenReturn(snapshot);
        SnapshotResourceResolver resolver = new SnapshotResourceResolver(mapper);

        IamS1ResolvedResource resolved = resolver.resolve(8L);

        assertThat(resolved.datasourceId()).isEqualTo(5L);
        assertThat(resolved.snapshotId()).isEqualTo(8L);
    }

    @Test
    void snapshotResolverRejectsBrokenOwnership() {
        MetadataSnapshotMapper mapper = mock(MetadataSnapshotMapper.class);
        MetadataSnapshot orphan = new MetadataSnapshot();
        orphan.setId(8L);
        orphan.setDatasourceId(null);
        when(mapper.selectById(8L)).thenReturn(orphan);
        SnapshotResourceResolver resolver = new SnapshotResourceResolver(mapper);

        // 归属断链不能当成“无归属即可放行”
        assertThat(catchThrowable(() -> resolver.resolve(8L))).isInstanceOf(BusinessException.class);
        assertThat(catchThrowable(() -> resolver.resolve(9L))).isInstanceOf(BusinessException.class);
    }

    @Test
    void metadataEntityResolverUsesEntityMetadataOwnership() {
        MetadataEntityMapper mapper = mock(MetadataEntityMapper.class);
        when(mapper.selectDatasourceIdByEntityId(100L)).thenReturn(5L);
        when(mapper.selectDatasourceIdByEntityId(101L)).thenReturn(null);
        MetadataEntityResourceResolver resolver = new MetadataEntityResourceResolver(mapper);

        assertThat(resolver.resolve(100L).datasourceId()).isEqualTo(5L);
        // 实体没有数据源归属 → 拒绝
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.resolve(101L))
                .as("实体没有数据源归属必须拒绝")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void metadataColumnResolverResolvesSnapshotAndTable() {
        MetadataEntityMapper mapper = mock(MetadataEntityMapper.class);
        MetadataEntity column = new MetadataEntity();
        column.setId(100L);
        column.setEntityType(MetadataEntity.TYPE_COLUMN);
        column.setName("phone");
        column.setFqn("ds.db.orders.phone");
        column.setEntityMetadata("{\"datasource_id\":5,\"snapshot_id\":8}");
        when(mapper.selectById(100L)).thenReturn(column);
        MetadataColumnResourceResolver resolver = new MetadataColumnResourceResolver(mapper);

        IamS1ResolvedResource resolved = resolver.resolve(100L);

        assertThat(resolved.datasourceId()).isEqualTo(5L);
        assertThat(resolved.snapshotId()).isEqualTo(8L);
        assertThat(resolved.tableName()).isEqualTo("orders");
        assertThat(resolved.columnName()).isEqualTo("phone");
    }

    @Test
    void metadataColumnResolverRejectsNonColumnEntity() {
        MetadataEntityMapper mapper = mock(MetadataEntityMapper.class);
        MetadataEntity table = new MetadataEntity();
        table.setId(100L);
        table.setEntityType(MetadataEntity.TYPE_TABLE);
        when(mapper.selectById(100L)).thenReturn(table);
        MetadataColumnResourceResolver resolver = new MetadataColumnResourceResolver(mapper);

        // 用表实体 ID 冒充列必须被拒绝
        assertThat(catchThrowable(() -> resolver.resolve(100L))).isInstanceOf(BusinessException.class);
    }

    @Test
    void lineageRelationshipResolverUsesSourceEntityDatasource() {
        MetadataRelationshipMapper relationshipMapper = mock(MetadataRelationshipMapper.class);
        MetadataEntityMapper entityMapper = mock(MetadataEntityMapper.class);
        MetadataRelationship relationship = new MetadataRelationship();
        relationship.setId(99L);
        relationship.setSourceId(10L);
        when(relationshipMapper.selectById(99L)).thenReturn(relationship);
        when(entityMapper.selectDatasourceIdByEntityId(10L)).thenReturn(5L);
        LineageRelationshipResourceResolver resolver =
                new LineageRelationshipResourceResolver(relationshipMapper, entityMapper);

        IamS1ResolvedResource resolved = resolver.resolve(99L);

        assertThat(resolved.datasourceId()).isEqualTo(5L);
        assertThat(resolved.type()).isEqualTo(IamS1ResourceType.LINEAGE_RELATIONSHIP);
    }

    @Test
    void feedbackReviewResolverRejectsWhenColumnAndTaskDatasourcesDisagree() {
        UserFeedbackMapper feedbackMapper = mock(UserFeedbackMapper.class);
        DbColumnMetaMapper columnMetaMapper = mock(DbColumnMetaMapper.class);
        QueryTaskMapper queryTaskMapper = mock(QueryTaskMapper.class);
        FeedbackReviewResourceResolver resolver =
                new FeedbackReviewResourceResolver(feedbackMapper, columnMetaMapper, queryTaskMapper);

        UserFeedback feedback = new UserFeedback();
        feedback.setId(3L);
        feedback.setColumnMetaId(11L);
        feedback.setQueryTaskId(20L);
        when(feedbackMapper.selectById(3L)).thenReturn(feedback);
        DbColumnMeta column = new DbColumnMeta();
        column.setId(11L);
        column.setDatasourceId(5L);
        when(columnMetaMapper.selectById(11L)).thenReturn(column);
        QueryTask task = new QueryTask();
        task.setId(20L);
        task.setDatasourceId(6L);
        when(queryTaskMapper.selectById(20L)).thenReturn(task);

        Throwable error = catchThrowable(() -> resolver.resolve(3L));
        assertThat(error).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) error).getCode()).isEqualTo(409);
        assertThat(error.getMessage()).contains("不一致");
    }

    @Test
    void feedbackReviewResolverUsesColumnWhenBothSidesAgree() {
        UserFeedbackMapper feedbackMapper = mock(UserFeedbackMapper.class);
        DbColumnMetaMapper columnMetaMapper = mock(DbColumnMetaMapper.class);
        QueryTaskMapper queryTaskMapper = mock(QueryTaskMapper.class);
        FeedbackReviewResourceResolver resolver =
                new FeedbackReviewResourceResolver(feedbackMapper, columnMetaMapper, queryTaskMapper);

        UserFeedback feedback = new UserFeedback();
        feedback.setId(3L);
        feedback.setColumnMetaId(11L);
        feedback.setQueryTaskId(20L);
        when(feedbackMapper.selectById(3L)).thenReturn(feedback);
        DbColumnMeta column = new DbColumnMeta();
        column.setId(11L);
        column.setDatasourceId(5L);
        column.setSnapshotId(8L);
        column.setTableName("orders");
        column.setColumnName("phone");
        when(columnMetaMapper.selectById(11L)).thenReturn(column);
        QueryTask task = new QueryTask();
        task.setId(20L);
        task.setDatasourceId(5L);
        when(queryTaskMapper.selectById(20L)).thenReturn(task);

        IamS1ResolvedResource resolved = resolver.resolve(3L);

        assertThat(resolved.datasourceId()).isEqualTo(5L);
        assertThat(resolved.snapshotId()).isEqualTo(8L);
        assertThat(resolved.columnName()).isEqualTo("phone");
    }

    @Test
    void lineageRelationshipResolverRejectsMissingRelationshipOrBrokenSource() {
        MetadataRelationshipMapper relationshipMapper = mock(MetadataRelationshipMapper.class);
        MetadataEntityMapper entityMapper = mock(MetadataEntityMapper.class);
        LineageRelationshipResourceResolver resolver =
                new LineageRelationshipResourceResolver(relationshipMapper, entityMapper);

        assertThat(catchThrowable(() -> resolver.resolve(404L))).isInstanceOf(BusinessException.class);

        MetadataRelationship orphan = new MetadataRelationship();
        orphan.setId(99L);
        orphan.setSourceId(10L);
        when(relationshipMapper.selectById(99L)).thenReturn(orphan);
        when(entityMapper.selectDatasourceIdByEntityId(10L)).thenReturn(null);
        assertThat(catchThrowable(() -> resolver.resolve(99L))).isInstanceOf(BusinessException.class);
    }

    @Test
    void registryRejectsDuplicateResolversForOneType() {
        IamS1ResourceResolver first = stubResolver(IamS1ResourceType.DATASOURCE);
        IamS1ResourceResolver second = stubResolver(IamS1ResourceType.DATASOURCE);

        assertThat(catchThrowable(() -> new IamS1ResourceResolverRegistry(List.of(first, second))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void registryFailsClosedForUnregisteredType() {
        IamS1ResourceResolverRegistry registry =
                new IamS1ResourceResolverRegistry(List.of(stubResolver(IamS1ResourceType.DATASOURCE)));

        assertThat(registry.registeredTypes()).containsExactly(IamS1ResourceType.DATASOURCE);
        assertThat(catchThrowable(() -> registry.require(IamS1ResourceType.SNAPSHOT)))
                .isInstanceOf(BusinessException.class);
        assertThat(catchThrowable(() -> registry.require(null)))
                .isInstanceOf(BusinessException.class);
    }

    private static IamS1ResourceResolver stubResolver(IamS1ResourceType type) {
        return new IamS1ResourceResolver() {
            @Override
            public IamS1ResourceType supports() {
                return type;
            }

            @Override
            public IamS1ResolvedResource resolve(Object resourceId) {
                return IamS1ResolvedResource.of(type, null, null);
            }
        };
    }
}
