package com.dataocean.module.knowledge.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.entity.VectorIndexTask;
import com.dataocean.module.knowledge.enums.DocStatus;
import com.dataocean.module.knowledge.enums.ReviewStatus;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeReviewTaskMapper;
import com.dataocean.module.knowledge.service.VectorIndexTaskService;
import com.dataocean.module.knowledge.support.KnowledgeDependencySnapshotBuilder;
import com.dataocean.module.knowledge.support.KnowledgeOwnershipValidator;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 修复轮聚焦测试：知识文档的**历史版本链**归属保护。
 *
 * <p>文档级注解只解析文档与当前版本，但版本列表 / 版本详情 / 版本差异 / 审核记录 /
 * 来源快照 / 回滚 / 索引任务都会读到历史版本。任一版本的 datasourceId 或来源快照与文档不一致，
 * 都必须 409；尤其回滚会把历史版本内容**重新写入**并创建向量化任务，必须在写入之前失败。</p>
 */
class KnowledgeVersionOwnershipTest {

    @Test
    void listVersionsRejectsAnOldVersionFromAnotherDatasource() {
        Fixture fixture = new Fixture();
        fixture.doc(11L, 5L, 2);
        fixture.versions(11L, row(11L, 1, 5L, null), row(11L, 2, 6L, null));

        assertThatThrownBy(() -> fixture.service.listVersions(11L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));
    }

    @Test
    void listVersionsRejectsAVersionWhoseSnapshotBelongsToAnotherDatasource() {
        Fixture fixture = new Fixture();
        fixture.doc(11L, 5L, 2);
        fixture.versions(11L, row(11L, 1, 5L, 8L), row(11L, 2, 5L, null));
        fixture.snapshot(8L, 6L);

        assertThatThrownBy(() -> fixture.service.listVersions(11L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getMessage()).contains("快照");
                });
    }

    @Test
    void listVersionsRejectsAVersionWhoseSnapshotIsMissing() {
        Fixture fixture = new Fixture();
        fixture.doc(11L, 5L, 1);
        fixture.versions(11L, row(11L, 1, 5L, 8L));

        assertThatThrownBy(() -> fixture.service.listVersions(11L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));
    }

    @Test
    void listVersionsReturnsNothingWhenEverythingMatches() {
        Fixture fixture = new Fixture();
        fixture.doc(11L, 5L, 2);
        fixture.versions(11L, row(11L, 1, 5L, 8L), row(11L, 2, 5L, null));
        fixture.snapshot(8L, 5L);

        assertThat(fixture.service.listVersions(11L)).hasSize(2);
    }

    @Test
    void getVersionRejectsACrossSourceVersion() {
        Fixture fixture = new Fixture();
        fixture.doc(11L, 5L, 1);
        fixture.version(11L, 1, 6L, null);

        assertThatThrownBy(() -> fixture.service.getVersion(11L, 1))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));
    }

    @Test
    void rollbackRejectsAnAbnormalVersionWithoutWritingAnything() {
        Fixture fixture = new Fixture();
        KnowledgeDoc doc = fixture.doc(11L, 5L, 2);
        doc.setStatus(DocStatus.PUBLISHED.name());
        // 目标版本审核已通过，唯一的问题是它属于另一个数据源
        KnowledgeDocVersion target = row(11L, 1, 6L, null);
        target.setReviewStatus(ReviewStatus.APPROVED.name());
        fixture.version(11L, 1, 6L, null);
        when(fixture.versionMapper.selectOwnershipByDocAndVersionNo(11L, 1)).thenReturn(target);
        when(fixture.versionMapper.selectList(any())).thenReturn(List.of(target));
        when(fixture.versionMapper.selectOne(any())).thenReturn(target);

        assertThatThrownBy(() -> fixture.service.rollback(11L, 1))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));

        // 零状态修改、零向量任务：回滚不能在归属不合法时写入任何东西
        verify(fixture.versionMapper, never()).insert(any(KnowledgeDocVersion.class));
        verify(fixture.docMapper, never()).updateById(any(KnowledgeDoc.class));
        verify(fixture.vectorIndexTaskService, never()).createTask(
                anyLong(), any(), anyLong(), anyLong(), any(), any());
        verify(fixture.vectorIndexTaskService, never()).createTask(anyLong(), any(), anyLong());
    }

    @Test
    void vectorTasksFromAnotherDatasourceAreRejected() {
        Fixture fixture = new Fixture();
        fixture.doc(11L, 5L, 1);
        VectorIndexTask foreign = VectorIndexTask.builder()
                .id(3L).datasourceId(6L).targetType("DOC").targetId(11L).build();
        when(fixture.vectorIndexTaskService.listTasksByTarget("DOC", 11L)).thenReturn(List.of(foreign));

        assertThatThrownBy(() -> fixture.service.listVectorTasksOfDocument(11L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));
    }

    @Test
    void vectorTasksWithoutDatasourceAreRejectedToo() {
        Fixture fixture = new Fixture();
        fixture.doc(11L, 5L, 1);
        VectorIndexTask orphan = VectorIndexTask.builder()
                .id(3L).datasourceId(null).targetType("DOC").targetId(11L).build();
        when(fixture.vectorIndexTaskService.listTasksByTarget("DOC", 11L)).thenReturn(List.of(orphan));

        assertThatThrownBy(() -> fixture.service.listVectorTasksOfDocument(11L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void vectorTasksInsideTheDocumentSourceAreReturned() {
        Fixture fixture = new Fixture();
        fixture.doc(11L, 5L, 1);
        VectorIndexTask own = VectorIndexTask.builder()
                .id(3L).datasourceId(5L).targetType("DOC").targetId(11L).build();
        when(fixture.vectorIndexTaskService.listTasksByTarget("DOC", 11L)).thenReturn(List.of(own));

        assertThat(fixture.service.listVectorTasksOfDocument(11L)).hasSize(1);
    }

    @Test
    void createVersionRejectsACrossSourceSnapshotBeforeInserting() {
        Fixture fixture = new Fixture();
        fixture.doc(11L, 5L, 1);
        fixture.snapshot(8L, 6L);

        // 客户端可以指定 snapshotId（生成草稿）：与其让跨源版本落库、之后再在读取时 409，
        // 不如在写入前就拒绝
        assertThatThrownBy(() -> fixture.service.createVersion(11L, "content", "MANUAL", 8L, "x"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));
        verify(fixture.versionMapper, never()).insert(any(KnowledgeDocVersion.class));
    }

    // ---------- 夹具 ----------

    private static KnowledgeDocVersion row(Long docId, int versionNo, Long datasourceId, Long snapshotId) {
        KnowledgeDocVersion version = new KnowledgeDocVersion();
        version.setId((long) (docId * 100 + versionNo));
        version.setDocId(docId);
        version.setVersionNo(versionNo);
        version.setDatasourceId(datasourceId);
        version.setMetadataSnapshotId(snapshotId);
        return version;
    }

    private static final class Fixture {
        private final KnowledgeDocVersionMapper versionMapper = mock(KnowledgeDocVersionMapper.class);
        private final KnowledgeDocMapper docMapper = mock(KnowledgeDocMapper.class);
        private final MetadataSnapshotMapper snapshotMapper = mock(MetadataSnapshotMapper.class);
        private final VectorIndexTaskService vectorIndexTaskService = mock(VectorIndexTaskService.class);
        private final KnowledgeOwnershipValidator ownershipValidator =
                new KnowledgeOwnershipValidator(versionMapper, snapshotMapper);
        private final KnowledgeVersionServiceImpl service = new KnowledgeVersionServiceImpl(
                versionMapper,
                docMapper,
                mock(KnowledgeChunkMapper.class),
                mock(KnowledgeReviewTaskMapper.class),
                snapshotMapper,
                vectorIndexTaskService,
                mock(KnowledgeDependencySnapshotBuilder.class),
                ownershipValidator,
                mock(UserMapper.class));

        KnowledgeDoc doc(Long id, Long datasourceId, Integer currentVersion) {
            KnowledgeDoc doc = KnowledgeDoc.builder()
                    .id(id)
                    .datasourceId(datasourceId)
                    .currentVersion(currentVersion)
                    .status(DocStatus.DRAFT.name())
                    .build();
            when(docMapper.selectById(id)).thenReturn(doc);
            return doc;
        }

        void versions(Long docId, KnowledgeDocVersion... rows) {
            when(versionMapper.selectList(any())).thenReturn(List.of(rows));
        }

        /**
         * getVersion 走 `selectOne`，文档解析器走 `selectOwnershipByDocAndVersionNo`：
         * 两条读取路径都要给出同一行，测试才反映真实调用。
         */
        void version(Long docId, int versionNo, Long datasourceId, Long snapshotId) {
            KnowledgeDocVersion row = row(docId, versionNo, datasourceId, snapshotId);
            when(versionMapper.selectOwnershipByDocAndVersionNo(docId, versionNo)).thenReturn(row);
            when(versionMapper.selectOne(any())).thenReturn(row);
        }

        void snapshot(Long id, Long datasourceId) {
            MetadataSnapshot snapshot = new MetadataSnapshot();
            snapshot.setId(id);
            snapshot.setDatasourceId(datasourceId);
            when(snapshotMapper.selectById(id)).thenReturn(snapshot);
        }
    }
}
