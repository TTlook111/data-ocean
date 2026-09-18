package com.dataocean.module.query.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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
import com.dataocean.module.query.entity.dto.IamS1QueryAskRequestDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1TableRequestDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.assertj.core.api.Assertions.assertThat;
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
    @Mock private DatasourceMapper datasourceMapper;
    @Mock private DatasourceSecretMapper datasourceSecretMapper;
    @Mock private DatasourceSecretService datasourceSecretService;
    @Mock private KnowledgeChunkMapper knowledgeChunkMapper;
    @Mock private AuditLogService auditLogService;
    @Mock private com.dataocean.module.metadata.service.SchemaSnapshotService schemaSnapshotService;
    @Mock private com.dataocean.module.permission.service.DataMaskingService maskingService;
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

        IamS1QueryAskRequestDTO request = new IamS1QueryAskRequestDTO();
        request.setProtocolVersion("IAM-SIMPLE-1"); request.setDatasourceId(1L); request.setQuestion("查询订单");
        IamS1TableRequestDTO table = new IamS1TableRequestDTO(); table.setTableName("orders");
        table.setReferencedColumns(new java.util.LinkedHashSet<>(List.of("id")));
        table.setColumnUsages(Map.of("id", Set.of(IamS1ColumnUsage.PROJECTION)));
        request.setTables(List.of(table));

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

    private IamS1DataAuthorizationSnapshot snapshot() {
        IamS1FieldProtectionVO field = new IamS1FieldProtectionVO(1L, "orders", "id", "NORMAL", null, "normal");
        IamS1TablePermissionVO table = new IamS1TablePermissionVO(true, "ALLOWED", "orders", List.of("id"), List.of(), List.of(field), List.of());
        return new IamS1DataAuthorizationSnapshot(true, "ALLOWED", "IAM-SIMPLE-1", 7L, 1L, "db", 88L,
                100L, LocalDateTime.now(), null, List.of(table));
    }
}
