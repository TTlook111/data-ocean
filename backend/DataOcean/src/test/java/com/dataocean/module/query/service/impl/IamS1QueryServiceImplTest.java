package com.dataocean.module.query.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.audit.service.AuditLogService;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceSecretMapper;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.DatasourceSecret;
import com.dataocean.module.datasource.service.DatasourceSecretService;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot;
import com.dataocean.module.permission.s1.entity.vo.IamS1FieldProtectionVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1TablePermissionVO;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1DataAuthorizationResolver;
import com.dataocean.module.permission.s1.enums.IamS1ColumnUsage;
import com.dataocean.module.query.client.IamS1PythonClient;
import com.dataocean.module.query.controller.IamS1QuerySseController;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.query.service.IamS1RowBindingService;
import com.dataocean.module.query.service.ConversationService;
import com.dataocean.module.query.service.ConversationContextSummaryService;
import com.dataocean.module.query.entity.dto.ConversationContextDTO;
import com.dataocean.module.query.entity.dto.IamS1QueryAskRequestDTO;
import com.dataocean.module.query.entity.vo.QueryTaskVO;
import com.dataocean.module.query.entity.vo.ConversationMessageVO;
import com.dataocean.module.permission.s1.entity.dto.IamS1TableRequestDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class IamS1QueryServiceImplTest {
    @Mock private QueryTaskMapper queryTaskMapper;
    @Spy private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    @Mock private IamS1DataAuthorizationResolver dataResolver;
    @Mock private IamS1AuthorizationResolver authorizationResolver;
    @Mock private IamS1RowBindingService rowBindingService;
    @Mock private IamS1PythonClient pythonClient;
    @Mock private IamS1QuerySseController sseController;
    @Mock private ConversationService conversationService;
    @Mock private ConversationContextSummaryService conversationContextSummaryService;
    @Mock private com.dataocean.module.glossary.mapper.GlossaryTermMapper glossaryTermMapper;
    @Mock private com.dataocean.module.metadata.service.MetadataEntityService metadataEntityService;
    @Mock private com.dataocean.module.metadata.service.MetadataRelationshipService metadataRelationshipService;
    @Mock private DatasourceMapper datasourceMapper;
    @Mock private DatasourceSecretMapper datasourceSecretMapper;
    @Mock private DatasourceSecretService datasourceSecretService;
    @Mock private KnowledgeChunkMapper knowledgeChunkMapper;
    @Mock private AuditLogService auditLogService;
    @Mock private com.dataocean.module.metadata.service.SchemaSnapshotService schemaSnapshotService;
    @Mock private com.dataocean.common.security.DataMaskingService maskingService;
    @InjectMocks private IamS1QueryServiceImpl service;

    @org.junit.jupiter.api.BeforeAll
    static void initMyBatisTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), QueryTask.class);
    }

    private QueryTask task;

    @BeforeEach
    void setUp() {
        task = QueryTask.builder().id(17L).taskId("task-1").userId(7L).datasourceId(1L)
                .iamProtocolVersion("IAM-SIMPLE-1").permissionRevision(100L).activeMetadataSnapshotId(88L)
                .status("PROCESSING").iamResourceRequest("[{\"tableName\":\"orders\",\"referencedColumns\":[\"id\"],\"columnUsages\":{\"id\":[\"PROJECTION\"]}}]")
                .iamExecutionSnapshot("{\"resources\":[]}").build();
        lenient().when(queryTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
    }

    @Test
    void completeRejectsEmptyUsedColumnsAndSourceTraceBeforePersistingData() throws Exception {
        service.complete("task-1", objectMapper.writeValueAsString(Map.of(
                "taskId", "task-1", "protocolVersion", "IAM-SIMPLE-1", "status", "COMPLETED",
                "usedColumns", List.of(), "sourceTrace", List.of(), "columns", List.of(Map.of("name", "id")),
                "data", List.of(Map.of("id", 1)))));

        verify(queryTaskMapper).update(any(), any());
        verify(auditLogService, never()).recordAudit(17L);
    }

    @Test
    void completePersistsOnlyWhenSourceTraceAndCurrentSnapshotAreComplete() throws Exception {
        MetadataSnapshot metadata = new MetadataSnapshot();
        metadata.setId(88L);
        when(schemaSnapshotService.getPublishedSnapshot(1L)).thenReturn(metadata);
        when(dataResolver.resolve(any())).thenReturn(snapshot());
        when(maskingService.maskResultByFields(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.complete("task-1", objectMapper.writeValueAsString(Map.of(
                "taskId", "task-1", "protocolVersion", "IAM-SIMPLE-1", "status", "COMPLETED",
                "usedColumns", List.of("orders.id"), "usedTables", List.of("orders"),
                "sourceTrace", List.of(Map.of("outputColumn", "id", "sources", List.of("orders.id"))),
                "columns", List.of(Map.of("name", "id")), "data", List.of(Map.of("id", 1)))));

        verify(queryTaskMapper).update(any(), any());
        verify(auditLogService).recordAudit(17L);
    }

    @Test
    void submitDispatchesPythonOnlyAfterTransactionCommit() {
        MetadataSnapshot metadata = new MetadataSnapshot();
        metadata.setId(88L);
        when(authorizationResolver.hasGlobalFunction(eq(7L), any())).thenReturn(true);
        when(schemaSnapshotService.getPublishedSnapshot(1L)).thenReturn(metadata);
        when(dataResolver.resolve(any())).thenReturn(snapshot());
        when(rowBindingService.build(any())).thenReturn(List.of());
        when(knowledgeChunkMapper.selectList(any())).thenReturn(List.of());
        Datasource datasource = new Datasource();
        datasource.setId(1L); datasource.setHost("localhost"); datasource.setPort(3306); datasource.setDatabaseName("db");
        DatasourceSecret secret = new DatasourceSecret(); secret.setDatasourceId(1L); secret.setUsername("u"); secret.setEncryptedPassword("enc");
        when(datasourceMapper.selectById(1L)).thenReturn(datasource);
        when(datasourceSecretMapper.selectOne(any())).thenReturn(secret);
        when(datasourceSecretService.decrypt("enc")).thenReturn("pwd");

        IamS1QueryAskRequestDTO request = askRequest();

        TransactionSynchronizationManager.initSynchronization();
        try {
            String taskId = service.submit(7L, request);
            verify(pythonClient, never()).executeAsync(any(), any(), any());
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            verify(pythonClient).executeAsync(eq(taskId), any(), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void submitPersistsConversationAndSendsScopedContextAndFewShot() {
        MetadataSnapshot metadata = new MetadataSnapshot();
        metadata.setId(88L);
        when(authorizationResolver.hasGlobalFunction(eq(7L), any())).thenReturn(true);
        when(schemaSnapshotService.getPublishedSnapshot(1L)).thenReturn(metadata);
        when(dataResolver.resolve(any())).thenReturn(snapshot());
        when(rowBindingService.build(any())).thenReturn(List.of());
        when(knowledgeChunkMapper.selectList(any())).thenReturn(List.of());
        when(conversationService.getOrCreateConversation(7L, 1L, null, "查询订单")).thenReturn(42L);
        when(conversationContextSummaryService.buildQueryContext(42L, 7L))
                .thenReturn(ConversationContextDTO.builder()
                        .history(List.of(Map.of("role", "user", "content", "上一轮订单")))
                        .summary(Map.of("intent", "订单统计"))
                        .build());
        com.dataocean.module.glossary.entity.GlossaryTerm glossaryTerm =
                new com.dataocean.module.glossary.entity.GlossaryTerm();
        glossaryTerm.setId(9L);
        glossaryTerm.setName("订单");
        glossaryTerm.setDisplayName("订单");
        glossaryTerm.setStatus(com.dataocean.module.glossary.entity.GlossaryTerm.STATUS_APPROVED);
        when(glossaryTermMapper.selectList(any())).thenReturn(List.of(glossaryTerm));
        when(metadataRelationshipService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        QueryTask previous = QueryTask.builder().userId(7L).datasourceId(1L)
                .iamProtocolVersion("IAM-SIMPLE-1").status("COMPLETED")
                .question("上一轮订单").resultSql("SELECT id FROM orders WHERE id = :iam_s1_previous")
                .usedTables("[\"orders\"]").usedColumns("[\"orders.id\"]").build();
        QueryTask foreign = QueryTask.builder().userId(7L).datasourceId(1L)
                .iamProtocolVersion("IAM-SIMPLE-1").status("COMPLETED")
                .question("客户信息").resultSql("SELECT secret FROM customers")
                .usedTables("[\"customers\"]").usedColumns("[\"customers.secret\"]").build();
        when(queryTaskMapper.selectList(any())).thenReturn(List.of(previous, foreign));
        Datasource datasource = new Datasource();
        datasource.setId(1L); datasource.setHost("localhost"); datasource.setPort(3306); datasource.setDatabaseName("db");
        DatasourceSecret secret = new DatasourceSecret(); secret.setDatasourceId(1L); secret.setUsername("u"); secret.setEncryptedPassword("enc");
        when(datasourceMapper.selectById(1L)).thenReturn(datasource);
        when(datasourceSecretMapper.selectOne(any())).thenReturn(secret);
        when(datasourceSecretService.decrypt("enc")).thenReturn("pwd");

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.submit(7L, askRequest());
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(conversationService).saveUserMessage(42L, "查询订单");
        verify(pythonClient).executeAsync(any(), body.capture(), any());
        assertThat(body.getValue().get("conversationHistory")).isEqualTo(
                List.of(Map.of("role", "user", "content", "上一轮订单")));
        assertThat(body.getValue().get("conversationSummary")).isEqualTo(Map.of("intent", "订单统计"));
        assertThat(body.getValue().get("glossaryTerms").toString()).contains("订单");
        assertThat(body.getValue().get("fewShotExamples").toString()).contains("上一轮订单");
        assertThat(body.getValue().get("fewShotExamples").toString()).contains("datasourceId=1");
        assertThat(body.getValue().get("fewShotExamples").toString()).contains("activeMetadataSnapshotId=88");
        assertThat(body.getValue().get("fewShotExamples").toString()).doesNotContain("customers.secret");
    }

    @Test
    void completePersistsAssistantMessageAndRefreshesSummary() throws Exception {
        task.setConversationId(42L);
        when(schemaSnapshotService.getPublishedSnapshot(1L)).thenReturn(metadataSnapshot(88L));
        when(dataResolver.resolve(any())).thenReturn(snapshot());
        when(maskingService.maskResultByFields(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(authorizationResolver.hasGlobalFunction(eq(7L), any())).thenReturn(true);
        when(queryTaskMapper.update(any(), any())).thenReturn(1);

        service.complete("task-1", objectMapper.writeValueAsString(Map.of(
                "taskId", "task-1", "protocolVersion", "IAM-SIMPLE-1", "status", "COMPLETED",
                "usedColumns", List.of("orders.id"), "usedTables", List.of("orders"),
                "sourceTrace", List.of(Map.of("outputColumn", "id", "sources", List.of("orders.id"))),
                "columns", List.of(Map.of("name", "id")), "data", List.of(Map.of("id", 1)),
                "chartConfig", Map.of("series", List.of(Map.of("name", "id", "data", List.of(1)))))));

        verify(conversationService).saveAssistantMessage(eq(42L), any(), eq("task-1"), any());
        verify(conversationContextSummaryService).refreshAsync(42L, 7L);
    }

    @Test
    void failedCompletionPersistsFailureMessageAndRefreshesSummary() throws Exception {
        task.setConversationId(42L);
        when(queryTaskMapper.update(any(), any())).thenReturn(1);

        service.complete("task-1", objectMapper.writeValueAsString(Map.of(
                "taskId", "task-1", "protocolVersion", "IAM-SIMPLE-1", "status", "FAILED",
                "error", "SQL 未通过校验")));

        verify(conversationService).saveAssistantMessage(eq(42L), eq("SQL 未通过校验"), eq("task-1"), any());
        verify(conversationContextSummaryService).refreshAsync(42L, 7L);
    }

    @Test
    void chartIsDroppedWhenAnyOutputColumnRequiresMasking() throws Exception {
        var method = IamS1QueryServiceImpl.class.getDeclaredMethod(
                "safeChartConfig", Object.class, Map.class);
        method.setAccessible(true);
        Map<String, Object> chart = Map.of(
                "xAxis", Map.of("data", List.of("13800000000")),
                "dataset", Map.of("source", List.of(Map.of("phone", "13800000000"))),
                "series", List.of(Map.of("name", "phone", "data", List.of(Map.of(
                        "name", "13800000000", "value", 1)))));

        Object sanitized = method.invoke(service, chart, Map.of("phone", "PHONE"));

        assertThat(sanitized).isNull();
    }

    @Test
    void glossaryConvertsRealMetadataFqnToFirewallTableColumnContract() throws Exception {
        var term = approvedTerm(9L, "订单");
        var relation = new com.dataocean.module.metadata.entity.MetadataRelationship();
        relation.setSourceId(9L);
        relation.setTargetId(20L);
        var entity = new com.dataocean.module.metadata.entity.MetadataEntity();
        entity.setId(20L);
        entity.setFqn("db.sales.orders.id");
        when(glossaryTermMapper.selectList(any())).thenReturn(List.of(term));
        when(metadataRelationshipService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(relation));
        when(metadataEntityService.listByIds(any())).thenReturn(List.of(entity));

        List<Map<String, Object>> glossary = invokeGlossary(snapshot());

        assertThat(glossary).hasSize(1);
        assertThat(glossary.get(0).get("columns")).isEqualTo(List.of("orders.id"));
    }

    @Test
    void glossaryDropsTermWhenRelatedEntityIsMissingOrCrossDatasource() throws Exception {
        var term = approvedTerm(9L, "订单");
        var relation = new com.dataocean.module.metadata.entity.MetadataRelationship();
        relation.setSourceId(9L);
        relation.setTargetId(20L);
        when(glossaryTermMapper.selectList(any())).thenReturn(List.of(term));
        when(metadataRelationshipService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(relation));
        when(metadataEntityService.listByIds(any())).thenReturn(List.of());
        assertThat(invokeGlossary(snapshot())).isEmpty();

        var crossDatasource = new com.dataocean.module.metadata.entity.MetadataEntity();
        crossDatasource.setId(20L);
        crossDatasource.setFqn("other.sales.orders.id");
        when(metadataEntityService.listByIds(any())).thenReturn(List.of(crossDatasource));
        assertThat(invokeGlossary(snapshot())).isEmpty();
    }

    @Test
    void glossaryRelationReadFailureReturnsEmptyGlossary() throws Exception {
        when(glossaryTermMapper.selectList(any())).thenReturn(List.of(approvedTerm(9L, "订单")));
        when(metadataRelationshipService.list(any(LambdaQueryWrapper.class)))
                .thenThrow(new IllegalStateException("relationship store down"));

        assertThat(invokeGlossary(snapshot())).isEmpty();
    }

    private com.dataocean.module.glossary.entity.GlossaryTerm approvedTerm(Long id, String name) {
        var term = new com.dataocean.module.glossary.entity.GlossaryTerm();
        term.setId(id);
        term.setName(name);
        term.setDisplayName(name);
        term.setStatus(com.dataocean.module.glossary.entity.GlossaryTerm.STATUS_APPROVED);
        return term;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> invokeGlossary(IamS1DataAuthorizationSnapshot snapshot) throws Exception {
        var method = IamS1QueryServiceImpl.class.getDeclaredMethod(
                "loadApprovedGlossaryTerms", IamS1DataAuthorizationSnapshot.class);
        method.setAccessible(true);
        return (List<Map<String, Object>>) method.invoke(service, snapshot);
    }

    @Test
    void conversationHistoryIsDeniedAfterQueryUseRevocation() {
        when(authorizationResolver.hasGlobalFunction(7L, "query:use")).thenReturn(false);

        assertThatThrownBy(() -> service.conversationMessages(42L, 7L, 1, 50))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("问数功能");
        verifyNoConversationRead();
    }

    private void verifyNoConversationRead() {
        org.mockito.Mockito.verifyNoInteractions(conversationService);
    }

    private MetadataSnapshot metadataSnapshot(Long id) {
        MetadataSnapshot metadata = new MetadataSnapshot();
        metadata.setId(id);
        return metadata;
    }

    @Test
    void submitPushesSseThroughTheSameGatedReadPathAsRest() throws Exception {
        MetadataSnapshot metadata = new MetadataSnapshot();
        metadata.setId(88L);
        when(schemaSnapshotService.getPublishedSnapshot(1L)).thenReturn(metadata);
        when(dataResolver.resolve(any())).thenReturn(snapshot());
        when(rowBindingService.build(any())).thenReturn(List.of());
        when(knowledgeChunkMapper.selectList(any())).thenReturn(List.of());
        when(authorizationResolver.hasGlobalFunction(eq(7L), eq("query:use"))).thenReturn(true);
        when(authorizationResolver.hasGlobalFunction(eq(7L), eq("query:sql:view"))).thenReturn(false);
        when(authorizationResolver.hasGlobalFunction(eq(7L), eq("query:export"))).thenReturn(false);
        when(maskingService.maskResultByFields(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        Datasource datasource = new Datasource();
        datasource.setId(1L); datasource.setHost("localhost"); datasource.setPort(3306); datasource.setDatabaseName("db");
        DatasourceSecret secret = new DatasourceSecret(); secret.setDatasourceId(1L); secret.setUsername("u"); secret.setEncryptedPassword("enc");
        when(datasourceMapper.selectById(1L)).thenReturn(datasource);
        when(datasourceSecretMapper.selectOne(any())).thenReturn(secret);
        when(datasourceSecretService.decrypt("enc")).thenReturn("pwd");
        // 任务已按当前权限落库了注入后的 SQL，但该用户没有 query:sql:view。
        task.setStatus("COMPLETED");
        task.setResultSql("SELECT id FROM orders LIMIT 10000");
        task.setResultData("[{\"id\":1}]");
        task.setResultColumns("[{\"name\":\"id\"}]");
        task.setUsedColumns("[\"orders.id\"]");
        task.setIamSourceTrace("{\"entries\":[{\"outputColumn\":\"id\",\"sources\":[\"orders.id\"]}]}");
        task.setIamCapabilities("{\"query\":true,\"viewSql\":false,\"export\":false}");

        ArgumentCaptor<Consumer<String>> callback = ArgumentCaptor.forClass(Consumer.class);
        String taskId;
        TransactionSynchronizationManager.initSynchronization();
        try {
            taskId = service.submit(7L, askRequest());
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(pythonClient).executeAsync(eq(taskId), any(), callback.capture());
        callback.getValue().accept(objectMapper.writeValueAsString(Map.of(
                "taskId", taskId, "protocolVersion", "IAM-SIMPLE-1", "status", "COMPLETED",
                "usedColumns", List.of("orders.id"), "usedTables", List.of("orders"),
                "sourceTrace", List.of(Map.of("outputColumn", "id", "sources", List.of("orders.id"))),
                "columns", List.of(Map.of("name", "id")), "data", List.of(Map.of("id", 1)),
                "sql", "SELECT id FROM orders LIMIT 10000")));

        ArgumentCaptor<Object> pushed = ArgumentCaptor.forClass(Object.class);
        verify(sseController).sendResult(eq(taskId), pushed.capture());
        QueryTaskVO vo = (QueryTaskVO) pushed.getValue();
        assertThat(task.getResultSql()).isNotNull();
        assertThat(vo.getSql()).isNull();
        assertThat(vo.getCanViewSql()).isFalse();
    }

    @Test
    void ssePushesOnlyAnErrorWhenTheReadPathRefusesTheResult() throws Exception {
        MetadataSnapshot metadata = new MetadataSnapshot();
        metadata.setId(88L);
        when(schemaSnapshotService.getPublishedSnapshot(1L)).thenReturn(metadata).thenReturn(null);
        when(dataResolver.resolve(any())).thenReturn(snapshot());
        when(rowBindingService.build(any())).thenReturn(List.of());
        when(knowledgeChunkMapper.selectList(any())).thenReturn(List.of());
        when(authorizationResolver.hasGlobalFunction(eq(7L), any())).thenReturn(true);
        Datasource datasource = new Datasource();
        datasource.setId(1L); datasource.setHost("localhost"); datasource.setPort(3306); datasource.setDatabaseName("db");
        DatasourceSecret secret = new DatasourceSecret(); secret.setDatasourceId(1L); secret.setUsername("u"); secret.setEncryptedPassword("enc");
        when(datasourceMapper.selectById(1L)).thenReturn(datasource);
        when(datasourceSecretMapper.selectOne(any())).thenReturn(secret);
        when(datasourceSecretService.decrypt("enc")).thenReturn("pwd");

        ArgumentCaptor<Consumer<String>> callback = ArgumentCaptor.forClass(Consumer.class);
        String taskId;
        TransactionSynchronizationManager.initSynchronization();
        try {
            taskId = service.submit(7L, askRequest());
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(pythonClient).executeAsync(eq(taskId), any(), callback.capture());
        callback.getValue().accept("{\"taskId\":\"" + taskId
                + "\",\"protocolVersion\":\"IAM-SIMPLE-1\",\"status\":\"FAILED\"}");

        verify(sseController).sendError(eq(taskId), any());
        verify(sseController, never()).sendResult(any(), any());
    }

    @Test
    void failedTaskIsReadableWithItsRealErrorInsteadOfPermissionChanged() {
        MetadataSnapshot metadata = new MetadataSnapshot();
        metadata.setId(88L);
        when(schemaSnapshotService.getPublishedSnapshot(1L)).thenReturn(metadata);
        when(dataResolver.resolve(any())).thenReturn(snapshot());
        when(authorizationResolver.hasGlobalFunction(eq(7L), eq("query:use"))).thenReturn(true);
        task.setStatus("FAILED");
        task.setErrorMessage("SQL 未通过 S1 AST 校验");
        task.setIamFinalProtectionStatus("FAILED");

        QueryTaskVO vo = service.get("task-1", 7L);

        assertThat(vo.getStatus()).isEqualTo("FAILED");
        assertThat(vo.getErrorMessage()).isEqualTo("SQL 未通过 S1 AST 校验");
        assertThat(vo.getSql()).isNull();
    }

    @Test
    void getIsRefusedOnceQueryUseIsRevoked() {
        when(authorizationResolver.hasGlobalFunction(eq(7L), eq("query:use"))).thenReturn(false);

        assertThatThrownBy(() -> service.get("task-1", 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("问数功能");
    }

    private IamS1QueryAskRequestDTO askRequest() {
        IamS1QueryAskRequestDTO request = new IamS1QueryAskRequestDTO();
        request.setProtocolVersion("IAM-SIMPLE-1"); request.setDatasourceId(1L); request.setQuestion("查询订单");
        IamS1TableRequestDTO table = new IamS1TableRequestDTO(); table.setTableName("orders");
        table.setReferencedColumns(new java.util.LinkedHashSet<>(List.of("id")));
        table.setColumnUsages(Map.of("id", Set.of(IamS1ColumnUsage.PROJECTION)));
        request.setTables(List.of(table));
        return request;
    }

    @Test
    void completeRefusesToPersistWhenTheTraceCarriesConflictingMaskPolicies() throws Exception {
        MetadataSnapshot metadata = new MetadataSnapshot();
        metadata.setId(88L);
        when(schemaSnapshotService.getPublishedSnapshot(1L)).thenReturn(metadata);
        when(dataResolver.resolve(any())).thenReturn(maskedSnapshot());

        service.complete("task-1", objectMapper.writeValueAsString(Map.of(
                "taskId", "task-1", "protocolVersion", "IAM-SIMPLE-1", "status", "COMPLETED",
                "usedColumns", List.of("users.phone", "users.email"), "usedTables", List.of("users"),
                "sourceTrace", List.of(
                        Map.of("outputColumn", "x", "sources", List.of("users.phone")),
                        Map.of("outputColumn", "X", "sources", List.of("users.email"))),
                "columns", List.of(Map.of("name", "x")), "data", List.of(Map.of("x", "13800000000")))));

        // 冲突结果只能按其中一个策略脱敏，必须在落库前 fail-closed。
        verify(queryTaskMapper).update(any(), any());
        verify(auditLogService, never()).recordAudit(17L);
    }

    @Test
    void getRefusesToReturnWhenThePersistedTraceCarriesConflictingMaskPolicies() {
        MetadataSnapshot metadata = new MetadataSnapshot();
        metadata.setId(88L);
        when(schemaSnapshotService.getPublishedSnapshot(1L)).thenReturn(metadata);
        when(dataResolver.resolve(any())).thenReturn(maskedSnapshot());
        when(authorizationResolver.hasGlobalFunction(eq(7L), eq("query:use"))).thenReturn(true);
        task.setStatus("COMPLETED");
        task.setUsedColumns("[\"users.phone\",\"users.email\"]");
        task.setResultColumns("[{\"name\":\"x\"}]");
        task.setResultData("[{\"x\":\"13800000000\"}]");
        task.setIamSourceTrace("{\"entries\":[{\"outputColumn\":\"x\",\"sources\":[\"users.phone\"]},"
                + "{\"outputColumn\":\"X\",\"sources\":[\"users.email\"]}]}");

        assertThatThrownBy(() -> service.get("task-1", 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("脱敏策略冲突");
    }

    private IamS1DataAuthorizationSnapshot maskedSnapshot() {
        IamS1FieldProtectionVO phone = new IamS1FieldProtectionVO(2L, "users", "phone", "MASKED", "PHONE", "masked");
        IamS1FieldProtectionVO email = new IamS1FieldProtectionVO(4L, "users", "email", "MASKED", "EMAIL", "masked");
        IamS1TablePermissionVO table = new IamS1TablePermissionVO(true, "ALLOWED", "users", List.of("phone", "email"),
                List.of(), List.of(phone, email), List.of());
        return new IamS1DataAuthorizationSnapshot(true, "ALLOWED", "IAM-SIMPLE-1", 7L, 1L, "db", 88L,
                100L, LocalDateTime.now(), null, List.of(table));
    }

    private IamS1DataAuthorizationSnapshot snapshot() {
        IamS1FieldProtectionVO field = new IamS1FieldProtectionVO(1L, "orders", "id", "NORMAL", null, "normal");
        IamS1TablePermissionVO table = new IamS1TablePermissionVO(true, "ALLOWED", "orders", List.of("id"), List.of(), List.of(field), List.of());
        return new IamS1DataAuthorizationSnapshot(true, "ALLOWED", "IAM-SIMPLE-1", 7L, 1L, "db", 88L,
                100L, LocalDateTime.now(), null, List.of(table));
    }
}
