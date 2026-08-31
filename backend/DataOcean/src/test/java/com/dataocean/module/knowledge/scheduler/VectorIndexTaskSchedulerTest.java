package com.dataocean.module.knowledge.scheduler;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dataocean.module.knowledge.client.PythonRagClient;
import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.entity.VectorIndexTask;
import com.dataocean.module.knowledge.enums.DocStatus;
import com.dataocean.module.knowledge.enums.VectorTaskStatus;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.service.KnowledgeChunkService;
import com.dataocean.module.knowledge.service.VectorIndexTaskService;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.service.MetadataEntityService;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    @Mock
    private KnowledgeChunkService knowledgeChunkService;
    @Mock
    private MetadataEntityService metadataEntityService;

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
                Map.of(
                        "chunkType", "JOIN_PATH",
                        "chunkText", "### orders ↔ customers",
                        "tableName", "orders",
                        "relatedTables", List.of("orders", "customers"),
                        "relatedColumns", List.of("orders.customer_id", "customers.id"))
        ));
        when(pythonRagClient.vectorize(eq(task), any(), eq(false)))
                .thenReturn(Map.of("status", "COMPLETED", "vectorizedCount", 2));
        when(pythonRagClient.deleteDocVersionVectors(eq(task), any())).thenReturn(true);
        MetadataEntity ordersTable = new MetadataEntity();
        ordersTable.setId(101L);
        ordersTable.setEntityType(MetadataEntity.TYPE_TABLE);
        ordersTable.setName("orders");
        MetadataEntity customersTable = new MetadataEntity();
        customersTable.setId(102L);
        customersTable.setEntityType(MetadataEntity.TYPE_TABLE);
        customersTable.setName("customers");
        MetadataEntity ordersCustomerId = new MetadataEntity();
        ordersCustomerId.setId(201L);
        ordersCustomerId.setEntityType(MetadataEntity.TYPE_COLUMN);
        ordersCustomerId.setFqn("mysql_prod.app.orders.customer_id");
        MetadataEntity customersId = new MetadataEntity();
        customersId.setId(202L);
        customersId.setEntityType(MetadataEntity.TYPE_COLUMN);
        customersId.setFqn("mysql_prod.app.customers.id");
        when(metadataEntityService.getByDatasourceId(10L)).thenReturn(List.of(
                ordersTable, customersTable, ordersCustomerId, customersId));
        when(knowledgeDocMapper.selectById(99L)).thenReturn(doc);
        org.mockito.Mockito.doAnswer(invocation -> {
            Consumer<?> callback = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Consumer<Object> typed = (Consumer<Object>) callback;
            typed.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        scheduler.processTask(task);

        verify(knowledgeChunkMapper).delete(any(Wrapper.class));
        ArgumentCaptor<List> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeChunkService).saveBatch(chunksCaptor.capture());
        KnowledgeChunk joinChunk = (KnowledgeChunk) chunksCaptor.getValue().get(1);
        assertThat(joinChunk.getEntityIds()).contains("101", "102", "201", "202");

        verify(pythonRagClient).vectorize(eq(task), any(), eq(false));
        verify(knowledgeChunkMapper, org.mockito.Mockito.times(2)).update(eq(null), any(UpdateWrapper.class));
        verify(knowledgeDocMapper).updateById(org.mockito.ArgumentMatchers.<KnowledgeDoc>argThat(updated ->
                DocStatus.PUBLISHED.name().equals(updated.getStatus())));
        verify(vectorIndexTaskService).markCompleted(11L);
        verify(pythonRagClient).deleteDocVersionVectors(task, 2);
    }

    @Test
    void processTaskPublishesBeforeOldVectorCleanupAndSchedulesRetry() {
        VectorIndexTask task = VectorIndexTask.builder()
                .id(12L)
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

        when(knowledgeDocVersionMapper.selectOne(any(Wrapper.class))).thenReturn(version);
        when(metadataEntityService.getByDatasourceId(10L)).thenReturn(List.of());
        when(pythonRagClient.chunkDocument(eq(task), eq(version.getContent()))).thenReturn(List.of(
                Map.of("chunkType", "TABLE_DESC", "chunkText", "orders", "tableName", "orders")
        ));
        when(pythonRagClient.vectorize(eq(task), any(), eq(false)))
                .thenReturn(Map.of("status", "COMPLETED", "vectorizedCount", 1));
        when(pythonRagClient.deleteDocVersionVectors(eq(task), eq(2))).thenReturn(false);
        KnowledgeDoc doc = KnowledgeDoc.builder()
                .id(99L)
                .status(DocStatus.INDEXING.name())
                .build();
        when(knowledgeDocMapper.selectById(99L)).thenReturn(doc);
        org.mockito.Mockito.doAnswer(invocation -> {
            Consumer<?> callback = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Consumer<Object> typed = (Consumer<Object>) callback;
            typed.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        scheduler.processTask(task);

        verify(pythonRagClient).deleteDocVersionVectors(task, 2);
        verify(vectorIndexTaskService).markCompleted(12L);
        verify(vectorIndexTaskService).markCleanupPending(12L, "RAG 旧版本向量清理失败，等待调度重试");
        verify(knowledgeDocMapper).updateById(org.mockito.ArgumentMatchers.<KnowledgeDoc>argThat(updated ->
                DocStatus.PUBLISHED.name().equals(updated.getStatus())));
    }

    @Test
    void cleanupPendingTaskRetriesOnlyOldVectorDeletion() {
        VectorIndexTask task = VectorIndexTask.builder()
                .id(13L)
                .targetType("DOC")
                .targetId(99L)
                .knowledgeVersionNo(3)
                .previousVersionNo(2)
                .status(VectorTaskStatus.CLEANUP_PENDING.name())
                .build();
        when(pythonRagClient.deleteDocVersionVectors(task, 2)).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            Consumer<?> callback = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Consumer<Object> typed = (Consumer<Object>) callback;
            typed.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        scheduler.processTask(task);

        verify(pythonRagClient).deleteDocVersionVectors(task, 2);
        verify(pythonRagClient, org.mockito.Mockito.never()).vectorize(any(), any(), anyBoolean());
        verify(pythonRagClient, org.mockito.Mockito.never()).chunkDocument(any(), any());
        verify(vectorIndexTaskService).markCompleted(13L);
    }
}
