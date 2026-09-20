package com.dataocean.module.permission.s1.resource;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.support.KnowledgeOwnershipValidator;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.permission.s1.resource.impl.KnowledgeDocumentResourceResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批次 5 聚焦测试：知识文档资源的归属解析。
 *
 * <p>归属以 `knowledge_doc.datasource_id` 为准，并复核当前版本与版本上的来源快照：
 * 只信文档的 datasourceId 时，一条写错的版本会让调用者以“负责文档所在源”的身份
 * 读到另一个数据源的元数据内容。</p>
 */
class KnowledgeDocumentResourceResolverTest {

    @Test
    void resolvesDatasourceFromTheDocument() {
        Fixture fixture = new Fixture();
        fixture.doc(11L, 5L, 1);
        fixture.version(11L, 1, 5L, null);

        IamS1ResolvedResource resolved = fixture.resolver.resolve(11L);

        assertThat(resolved.datasourceId()).isEqualTo(5L);
        assertThat(resolved.type()).isEqualTo(IamS1ResourceType.KNOWLEDGE_DOCUMENT);
        assertThat(resolved.resourceId()).isEqualTo(11L);
    }

    @Test
    void resolvesWhenThereIsNoCurrentVersionYet() {
        Fixture fixture = new Fixture();
        // 新建文档还没有版本：仍然能解析出归属
        fixture.doc(11L, 5L, null);

        assertThat(fixture.resolver.resolve(11L).datasourceId()).isEqualTo(5L);
    }

    @Test
    void rejectsBlankOrUnparseableDocumentId() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.resolver.resolve(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fixture.resolver.resolve("not-a-number"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsMissingDocumentWith404() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.resolver.resolve(404L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(404));
    }

    @Test
    void rejectsDocumentWithoutDatasourceWith409() {
        Fixture fixture = new Fixture();
        fixture.doc(11L, null, 1);

        assertThatThrownBy(() -> fixture.resolver.resolve(11L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));
    }

    @Test
    void rejectsWhenTheCurrentVersionBelongsToAnotherDatasource() {
        Fixture fixture = new Fixture();
        fixture.doc(11L, 5L, 1);
        fixture.version(11L, 1, 6L, null);

        assertThatThrownBy(() -> fixture.resolver.resolve(11L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getMessage()).contains("版本");
                });
    }

    @Test
    void rejectsWhenTheSourceSnapshotBelongsToAnotherDatasource() {
        Fixture fixture = new Fixture();
        fixture.doc(11L, 5L, 1);
        fixture.version(11L, 1, 5L, 8L);
        fixture.snapshot(8L, 6L);

        assertThatThrownBy(() -> fixture.resolver.resolve(11L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getMessage()).contains("快照");
                });
    }

    @Test
    void rejectsWhenTheCurrentVersionRecordIsMissing() {
        Fixture fixture = new Fixture();
        // currentVersion 指向一个并不存在的版本记录：不能当成“尚无版本”放行
        fixture.doc(11L, 5L, 3);

        assertThatThrownBy(() -> fixture.resolver.resolve(11L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getMessage()).contains("当前版本");
                });
    }

    @Test
    void rejectsWhenTheCurrentVersionHasNoDatasource() {
        Fixture fixture = new Fixture();
        fixture.doc(11L, 5L, 1);
        fixture.version(11L, 1, null, null);

        assertThatThrownBy(() -> fixture.resolver.resolve(11L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));
    }

    @Test
    void treatsZeroOrMissingCurrentVersionAsNoVersionYet() {
        // currentVersion 为 0 表示历史“尚无版本”；null 表示新建文档尚未写入版本号。
        // 两者都是显式允许的状态，而不是“任意缺失都放行”——指向版本号时才必须存在。
        Fixture zero = new Fixture();
        zero.doc(11L, 5L, 0);
        assertThat(zero.resolver.resolve(11L).datasourceId()).isEqualTo(5L);

        Fixture missing = new Fixture();
        missing.doc(11L, 5L, null);
        assertThat(missing.resolver.resolve(11L).datasourceId()).isEqualTo(5L);
    }

    @Test
    void rejectsWhenTheSourceSnapshotIsMissingOrHasNoOwnership() {
        Fixture fixture = new Fixture();
        fixture.doc(11L, 5L, 1);
        fixture.version(11L, 1, 5L, 8L);

        // 快照不存在
        assertThatThrownBy(() -> fixture.resolver.resolve(11L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));

        Fixture orphan = new Fixture();
        orphan.doc(11L, 5L, 1);
        orphan.version(11L, 1, 5L, 8L);
        orphan.snapshot(8L, null);
        assertThatThrownBy(() -> orphan.resolver.resolve(11L))
                .isInstanceOf(BusinessException.class);
    }

    private static final class Fixture {
        private final KnowledgeDocMapper docMapper = mock(KnowledgeDocMapper.class);
        private final KnowledgeDocVersionMapper versionMapper = mock(KnowledgeDocVersionMapper.class);
        private final MetadataSnapshotMapper snapshotMapper = mock(MetadataSnapshotMapper.class);
        private final KnowledgeOwnershipValidator ownershipValidator =
                new KnowledgeOwnershipValidator(versionMapper, snapshotMapper);
        private final KnowledgeDocumentResourceResolver resolver =
                new KnowledgeDocumentResourceResolver(docMapper, ownershipValidator);

        void doc(Long id, Long datasourceId, Integer currentVersion) {
            KnowledgeDoc doc = KnowledgeDoc.builder()
                    .id(id)
                    .datasourceId(datasourceId)
                    .currentVersion(currentVersion)
                    .build();
            when(docMapper.selectById(id)).thenReturn(doc);
        }

        void version(Long docId, Integer versionNo, Long datasourceId, Long snapshotId) {
            KnowledgeDocVersion version = new KnowledgeDocVersion();
            version.setDocId(docId);
            version.setVersionNo(versionNo);
            version.setDatasourceId(datasourceId);
            version.setMetadataSnapshotId(snapshotId);
            when(versionMapper.selectOwnershipByDocAndVersionNo(docId, versionNo)).thenReturn(version);
        }

        void snapshot(Long id, Long datasourceId) {
            MetadataSnapshot snapshot = new MetadataSnapshot();
            snapshot.setId(id);
            snapshot.setDatasourceId(datasourceId);
            when(snapshotMapper.selectById(id)).thenReturn(snapshot);
        }
    }
}
