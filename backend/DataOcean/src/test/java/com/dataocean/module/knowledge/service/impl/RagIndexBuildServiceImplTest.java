package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.entity.RagIndexState;
import com.dataocean.module.knowledge.entity.RagIndexBuildChunk;
import com.dataocean.module.knowledge.enums.DocStatus;
import com.dataocean.module.knowledge.enums.ReviewStatus;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.mapper.RagIndexBuildChunkMapper;
import com.dataocean.module.knowledge.mapper.RagIndexBuildMapper;
import com.dataocean.module.knowledge.mapper.RagIndexStateMapper;
import com.dataocean.module.knowledge.client.PythonRagClient;
import com.dataocean.module.knowledge.service.impl.RagIndexBuildServiceImpl;
import com.dataocean.module.knowledge.support.KnowledgeSnapshotFactValidator;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.system.entity.vo.AiConfigVO;
import com.dataocean.module.system.service.AiConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagIndexBuildServiceImplTest {
    @Test
    void explicitConfirmationCreatesQueuedManifestButDoesNotSwitchActivePointer() {
        Fixture fixture = new Fixture();
        fixture.publishedSnapshot();
        fixture.publishedDocAndReviewedChunk();
        fixture.embeddingTarget();
        RagIndexState state = new RagIndexState();
        state.setDatasourceId(5L);
        state.setActiveBuildId(null);
        state.setNextGeneration(1L);
        when(fixture.stateMapper.selectForUpdate(5L)).thenReturn(state);
        when(fixture.stateMapper.selectById(5L)).thenReturn(null);

        var build = fixture.service.confirmBuild(5L, 88L, 9001L, true);

        assertThat(build.getStatus()).isEqualTo("QUEUED");
        assertThat(build.getSourceSnapshotId()).isEqualTo(88L);
        assertThat(build.getExpectedChunkCount()).isZero();
        assertThat(build.getCollectionName()).startsWith("dataocean_rag_ds5_b");
        assertThat(state.getActiveBuildId()).isNull();
        assertThat(state.getLatestBuildId()).isEqualTo(build.getBuildId());
        verify(fixture.buildChunkMapper, never()).insert(any(RagIndexBuildChunk.class));
        verify(fixture.pythonRagClient, never()).vectorizeBuild(any(), any(), any(), any(), any());
    }

    @Test
    void buildCannotBeQueuedWithoutTheExplicitConfirmationFlag() {
        Fixture fixture = new Fixture();
        assertThatThrownBy(() -> fixture.service.confirmBuild(5L, 88L, 9001L, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("明确确认");
        verify(fixture.snapshotMapper, never()).selectById(any());
    }

    private static final class Fixture {
        private final KnowledgeDocMapper docMapper = mock(KnowledgeDocMapper.class);
        private final KnowledgeDocVersionMapper versionMapper = mock(KnowledgeDocVersionMapper.class);
        private final KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        private final RagIndexBuildMapper buildMapper = mock(RagIndexBuildMapper.class);
        private final RagIndexStateMapper stateMapper = mock(RagIndexStateMapper.class);
        private final RagIndexBuildChunkMapper buildChunkMapper = mock(RagIndexBuildChunkMapper.class);
        private final MetadataSnapshotMapper snapshotMapper = mock(MetadataSnapshotMapper.class);
        private final PythonRagClient pythonRagClient = mock(PythonRagClient.class);
        private final AiConfigService aiConfigService = mock(AiConfigService.class);
        private final QueryTaskMapper queryTaskMapper = mock(QueryTaskMapper.class);
        private final KnowledgeSnapshotFactValidator factValidator = mock(KnowledgeSnapshotFactValidator.class);
        private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        private final RagIndexBuildServiceImpl service = new RagIndexBuildServiceImpl(
                docMapper, versionMapper, chunkMapper, buildMapper, stateMapper, buildChunkMapper,
                snapshotMapper, pythonRagClient, aiConfigService, factValidator, queryTaskMapper,
                new ObjectMapper(), transactionTemplate);

        void publishedSnapshot() {
            MetadataSnapshot snapshot = new MetadataSnapshot();
            snapshot.setId(88L);
            snapshot.setDatasourceId(5L);
            snapshot.setStatus(MetadataSnapshot.STATUS_PUBLISHED);
            when(snapshotMapper.selectById(88L)).thenReturn(snapshot);
        }

        void publishedDocAndReviewedChunk() {
            KnowledgeDoc doc = KnowledgeDoc.builder()
                    .id(10L).datasourceId(5L).currentVersion(1)
                    .status(DocStatus.PUBLISHED.name()).deleted(0).build();
            when(docMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(doc));
            KnowledgeDocVersion version = KnowledgeDocVersion.builder()
                    .id(20L).docId(10L).versionNo(1).metadataSnapshotId(88L)
                    .reviewStatus(ReviewStatus.APPROVED.name()).content("snapshot doc").build();
            when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(version);
        }

        void embeddingTarget() {
            AiConfigVO.EmbeddingConfig embedding = new AiConfigVO.EmbeddingConfig();
            embedding.setProviderId("qwen-test");
            embedding.setModel("text-embedding-v4");
            embedding.setDimension(1024);
            AiConfigVO config = new AiConfigVO();
            config.setActiveEmbedding(embedding);
            when(aiConfigService.getConfig()).thenReturn(config);
            AiConfigVO.Provider provider = new AiConfigVO.Provider();
            provider.setId("qwen-test");
            provider.setBaseUrl("https://embedding.test/v1");
            when(aiConfigService.getProvider("qwen-test", true)).thenReturn(provider);
            when(aiConfigService.decryptApiKey(provider)).thenReturn("test-key");
        }
    }
}
