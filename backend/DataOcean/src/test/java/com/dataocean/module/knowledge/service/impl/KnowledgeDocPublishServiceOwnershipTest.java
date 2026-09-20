package com.dataocean.module.knowledge.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.fieldtag.mapper.FieldTagMapper;
import com.dataocean.module.knowledge.client.PythonKnowledgeClient;
import com.dataocean.module.knowledge.client.PythonRagClient;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.support.KnowledgeDependencySnapshotBuilder;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 5 聚焦测试：AI 批量生成必须复核「请求的数据源」与「快照真实归属」一致。
 *
 * <p>接口同时收到 `datasourceId` 与 `snapshotId`。只校验快照（切面做的事）等于允许
 * 「用一个自己负责的快照 + 一个自己无权源的数据源 ID」去读别人数据源的表结构并交给 Python。
 * 校验必须发生在**任何读取与外部调用之前**。</p>
 */
class KnowledgeDocPublishServiceOwnershipTest {

    @Test
    void rejectsWhenTheSnapshotBelongsToAnotherDatasourceWithoutCallingPython() {
        Fixture fixture = new Fixture();
        // 快照 8 真实归属数据源 6，请求却传 5
        fixture.snapshot(8L, 6L);

        assertThatThrownBy(() -> fixture.service.batchGenerateFromSnapshot(5L, 8L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));

        // 零元数据读取、零 Python 调用、零文档写入
        verify(fixture.tableMetaMapper, never()).selectList(any());
        verify(fixture.pythonKnowledgeClient, never()).analyzeAndGenerate(
                anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void rejectsWhenTheSnapshotIsMissingOrHasNoOwnership() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.batchGenerateFromSnapshot(5L, 8L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(404));

        Fixture orphan = new Fixture();
        orphan.snapshot(8L, null);
        assertThatThrownBy(() -> orphan.service.batchGenerateFromSnapshot(5L, 8L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));
    }

    @Test
    void rejectsMissingRequiredParameters() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.batchGenerateFromSnapshot(null, 8L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fixture.service.batchGenerateFromSnapshot(5L, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void proceedsWhenTheSnapshotReallyBelongsToTheDatasource() {
        Fixture fixture = new Fixture();
        fixture.snapshot(8L, 5L);
        when(fixture.pythonKnowledgeClient.analyzeAndGenerate(anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(java.util.Map.of("docs", java.util.List.of()));

        // 归属一致后才会走到 Python；文档列表为空时按现有语义抛业务异常
        assertThatThrownBy(() -> fixture.service.batchGenerateFromSnapshot(5L, 8L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未返回任何文档");

        verify(fixture.pythonKnowledgeClient).analyzeAndGenerate(
                anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void generateDraftRejectsACrossSourceSnapshotBeforeCallingPython() {
        Fixture fixture = new Fixture();
        // 快照 8 归属数据源 6，而文档属于数据源 5
        fixture.snapshot(8L, 6L);
        com.dataocean.module.knowledge.entity.KnowledgeDoc doc =
                com.dataocean.module.knowledge.entity.KnowledgeDoc.builder()
                        .id(11L).datasourceId(5L).currentVersion(1).build();
        when(fixture.helper.requireDoc(11L)).thenReturn(doc);

        assertThatThrownBy(() -> fixture.service.generateDraft(11L, 8L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));

        // 零元数据读取、零 Python 调用、零版本写入
        verify(fixture.tableMetaMapper, never()).selectList(any());
        verify(fixture.pythonKnowledgeClient, never()).generateDraft(
                anyLong(), anyLong(), any(), any(), any());
    }

    private static final class Fixture {
        private final KnowledgeDocMapper docMapper = mock(KnowledgeDocMapper.class);
        private final PythonKnowledgeClient pythonKnowledgeClient = mock(PythonKnowledgeClient.class);
        private final DbTableMetaMapper tableMetaMapper = mock(DbTableMetaMapper.class);
        private final KnowledgeDocHelper helper = mock(KnowledgeDocHelper.class);
        private final MetadataSnapshotMapper snapshotMapper = mock(MetadataSnapshotMapper.class);
        private final com.dataocean.module.knowledge.support.KnowledgeOwnershipValidator ownershipValidator =
                new com.dataocean.module.knowledge.support.KnowledgeOwnershipValidator(
                        mock(KnowledgeDocVersionMapper.class), snapshotMapper);
        private final KnowledgeDocPublishService service;

        Fixture() {
            service = new KnowledgeDocPublishService(
                    docMapper,
                    mock(KnowledgeDocVersionMapper.class),
                    pythonKnowledgeClient,
                    mock(PythonRagClient.class),
                    mock(KnowledgeDependencySnapshotBuilder.class),
                    tableMetaMapper,
                    mock(DbColumnMetaMapper.class),
                    mock(TableRelationMapper.class),
                    mock(FieldTagMapper.class),
                    mock(TransactionTemplate.class),
                    helper,
                    snapshotMapper,
                    ownershipValidator);
        }

        void snapshot(Long id, Long datasourceId) {
            MetadataSnapshot snapshot = new MetadataSnapshot();
            snapshot.setId(id);
            snapshot.setDatasourceId(datasourceId);
            when(snapshotMapper.selectById(id)).thenReturn(snapshot);
        }
    }
}
