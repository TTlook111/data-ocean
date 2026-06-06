package com.dataocean.module.knowledge.scheduler;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dataocean.module.knowledge.client.PythonRagClient;
import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.entity.VectorIndexTask;
import com.dataocean.module.knowledge.enums.DocStatus;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.service.VectorIndexTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorIndexTaskSchedulerTest {

    @Mock
    private VectorIndexTaskService vectorIndexTaskService;
    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;
    @Mock
    private KnowledgeDocMapper knowledgeDocMapper;
    @Mock
    private KnowledgeDocVersionMapper knowledgeDocVersionMapper;
    @Mock
    private PythonRagClient pythonRagClient;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private VectorIndexTaskScheduler scheduler;

    @Test
    void processTaskChunksInPythonPersistsSnapshotPublishesAndCleansOldVectors() {
        VectorIndexTask task = VectorIndexTask.builder()
                .id(11L)
                .datasourceId(10L)
                .targetType("DOC")
                .targetId(99L)
                .metadataSnapshotId(5L)
                .knowledgeVersionNo(3)
                .previousVersionNo(2)
                .build();
        KnowledgeDocVersion version = KnowledgeDocVersion.builder()
                .docId(99L)
                .versionNo(3)
                .content("## 核心表说明\n### orders - 订单表")
                .build();
        KnowledgeDoc doc = KnowledgeDoc.builder()
                .id(99L)
                .status(DocStatus.INDEXING.name())
                .build();

        when(knowledgeDocVersionMapper.selectOne(any(Wrapper.class))).thenReturn(version);
        when(pythonRagClient.chunkDocument(eq(task), eq(version.getContent()))).thenReturn(List.of(
                Map.of("chunkType", "TABLE_DESC", "chunkText", "### orders", "tableName", "orders"),
                Map.of("chunkType", "JOIN_PATH", "chunkText", "### orders ↔ customers", "tableName", "orders")
        ));
        when(pythonRagClient.vectorize(eq(task), any(), eq(false)))
                .thenReturn(Map.of("status", "COMPLETED", "successCount", 2));
        when(knowledgeDocMapper.selectById(99L)).thenReturn(doc);
        org.mockito.Mockito.doAnswer(invocation -> {
            Consumer<?> callback = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Consumer<Object> typed = (Consumer<Object>) callback;
            typed.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        scheduler.processTask(task);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<KnowledgeChunk> chunkCaptor = ArgumentCaptor.forClass(KnowledgeChunk.class);
        verify(knowledgeChunkMapper).delete(any(Wrapper.class));
        verify(knowledgeChunkMapper, org.mockito.Mockito.times(2)).insert(chunkCaptor.capture());
        assertThat(chunkCaptor.getAllValues())
                .extracting(KnowledgeChunk::getChunkType)
                .containsExactly("TABLE_DESC", "JOIN_PATH");
        assertThat(chunkCaptor.getAllValues().get(0).getRelatedTable()).isEqualTo("orders");

        verify(pythonRagClient).vectorize(eq(task), any(), eq(false));
        verify(knowledgeChunkMapper, org.mockito.Mockito.times(2)).update(eq(null), any(UpdateWrapper.class));
        verify(knowledgeDocMapper).updateById(org.mockito.ArgumentMatchers.<KnowledgeDoc>argThat(updated ->
                DocStatus.PUBLISHED.name().equals(updated.getStatus())));
        verify(vectorIndexTaskService).markCompleted(11L);
        verify(pythonRagClient).deleteDocVersionVectors(task, 2);
    }
}
