package com.dataocean.module.query.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.query.entity.QueryAttempt;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.entity.dto.IamS1ExecutionBinding;
import com.dataocean.module.query.entity.dto.IamS1AttemptAuthorizeRequestDTO;
import com.dataocean.module.query.entity.dto.IamS1AttemptColumnEvidenceDTO;
import com.dataocean.module.query.entity.dto.IamS1AttemptResourceEvidenceDTO;
import com.dataocean.module.query.entity.dto.IamS1AttemptResultRequestDTO;
import com.dataocean.module.query.mapper.QueryAttemptMapper;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.query.service.ConversationService;
import com.dataocean.module.query.service.IamS1RowBindingService;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.enums.IamS1ColumnUsage;
import com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot;
import com.dataocean.module.permission.s1.entity.vo.IamS1FieldProtectionVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1TablePermissionVO;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1DataAuthorizationResolver;
import com.dataocean.common.security.DataMaskingService;
import com.dataocean.module.query.service.IamS1QueryAttemptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IamS1QueryAttemptServiceImplTest {
    private final QueryTaskMapper tasks = mock(QueryTaskMapper.class);
    private final QueryAttemptMapper attempts = mock(QueryAttemptMapper.class);
    private final IamS1DataAuthorizationResolver dataResolver = mock(IamS1DataAuthorizationResolver.class);
    private final IamS1AuthorizationResolver authorization = mock(IamS1AuthorizationResolver.class);
    private final IamS1RowBindingService bindings = mock(IamS1RowBindingService.class);
    private final ConversationService conversations = mock(ConversationService.class);
    private final com.dataocean.module.permission.s1.service.IamS1UserResourceService resources =
            mock(com.dataocean.module.permission.s1.service.IamS1UserResourceService.class);
    private final com.dataocean.module.metadata.service.SchemaSnapshotService snapshots =
            mock(com.dataocean.module.metadata.service.SchemaSnapshotService.class);
    private final DataMaskingService masking = mock(DataMaskingService.class);
    private final IamS1QueryServiceImpl queryService = mock(IamS1QueryServiceImpl.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final IamS1QueryAttemptServiceImpl service = new IamS1QueryAttemptServiceImpl(
            tasks, attempts, dataResolver, authorization, bindings, conversations, resources, snapshots,
            masking, mapper, queryService);

    private QueryTask task;

    @BeforeAll
    static void initMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, QueryTask.class);
        TableInfoHelper.initTableInfo(assistant, QueryAttempt.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        task = QueryTask.builder().id(1L).taskId("task-1").userId(7L).datasourceId(5L)
                .conversationId(42L).iamProtocolVersion("IAM-SIMPLE-1").status("PROCESSING")
                .permissionRevision(9L).activeMetadataSnapshotId(88L)
                .createdAt(LocalDateTime.now()).question("统计订单").build();
        when(tasks.selectByTaskIdForUpdate("task-1")).thenReturn(task);
        when(dataResolver.currentPermissionRevision()).thenReturn(9L);
        when(authorization.hasGlobalFunction(7L, "query:use")).thenReturn(true);
        when(conversations.isVisible(42L, 7L)).thenReturn(true);
        when(conversations.isActiveTurn(42L, "task-1")).thenReturn(true);
        MetadataSnapshot metadata = new MetadataSnapshot();
        metadata.setId(88L);
        when(snapshots.getPublishedSnapshot(5L)).thenReturn(metadata);
        when(queryService.writeJson(any())).thenAnswer(invocation -> mapper.writeValueAsString(invocation.getArgument(0)));
        when(queryService.buildSnapshot(eq("task-1"), any(), any())).thenReturn(Map.of("taskId", "task-1"));
        when(queryService.connectionConfig(5L)).thenReturn(Map.of("host", "fixture", "password", "db-secret"));
        when(queryService.safeSql(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void authorizeRejectsSqlHashMismatchBeforeResolverOrExecutionBindings() {
        IamS1AttemptAuthorizeRequestDTO request = authorizeRequest("SELECT id FROM orders", "0".repeat(64));

        assertThatThrownBy(() -> service.authorize("task-1", request))
                .hasMessageContaining("SQL 与 AST 证据摘要不一致");
        verify(dataResolver, never()).resolve(any());
        verify(bindings, never()).build(any());
    }

    @Test
    void authorizationRevisionChangeRejectsCandidateBeforeIssuingBindings() {
        String sql = "SELECT id FROM orders";
        when(dataResolver.currentPermissionRevision()).thenReturn(10L);

        Map<String, Object> decision = service.authorize("task-1", authorizeRequest(sql, sha256(sql)));

        assertThat(decision.get("allowed")).isEqualTo(false);
        verify(dataResolver, never()).resolve(any());
        verify(bindings, never()).build(any());
    }

    @Test
    void duplicateProtectedAttemptReturnsTheSameSafeRowsWithoutReexecuting() {
        String sql = "SELECT id FROM orders";
        IamS1AttemptAuthorizeRequestDTO request = authorizeRequest(sql, sha256(sql));
        QueryAttempt attempt = QueryAttempt.builder().taskId("task-1").attemptId("attempt-1")
                .attemptNo(1).sqlHash(request.getSqlHash()).status("PROTECTED")
                .permissionRevision(9L).activeMetadataSnapshotId(88L)
                .protectedData("[{\"id\":7}]").protectedColumns("[{\"name\":\"id\"}]")
                .sourceTrace("[]").maskedFields("{}").usedTables("[\"orders\"]")
                .usedColumns("[\"orders.id\"]").rowCount(1).executionTimeMs(2).build();
        when(attempts.selectForUpdate("task-1", "attempt-1")).thenReturn(attempt);

        Map<String, Object> decision = service.authorize("task-1", request);

        assertThat(decision.get("status")).isEqualTo("PROTECTED");
        assertThat(decision.get("data").toString()).contains("7");
        verify(bindings, never()).build(any());
        verify(dataResolver, never()).resolve(any());
    }

    @Test
    void authorizeKeepsRowBindingsOnlyInTheTransientDecisionResponse() {
        String sql = "SELECT id FROM orders";
        var request = authorizeRequest(sql, sha256(sql));
        var field = new IamS1FieldProtectionVO(101L, "orders", "id", "NORMAL", null, "normal");
        var table = new IamS1TablePermissionVO(true, "ALLOWED", "orders", List.of("id"), List.of(),
                List.of(field), List.of());
        var snapshot = new IamS1DataAuthorizationSnapshot(true, "ALLOWED", "IAM-SIMPLE-1", 7L, 5L,
                "fixture", 88L, 9L, LocalDateTime.now(), null, List.of(table));
        when(dataResolver.resolve(any())).thenReturn(snapshot);
        when(attempts.selectForUpdate("task-1", request.getAttemptId())).thenReturn(null);
        when(attempts.selectCount(any())).thenReturn(0L);
        when(bindings.build(snapshot)).thenReturn(List.of(new IamS1ExecutionBinding("grant-1", "STRING", "private-value")));

        Map<String, Object> decision = service.authorize("task-1", request);

        assertThat(decision.get("allowed")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<IamS1ExecutionBinding> returnedBindings = (List<IamS1ExecutionBinding>) decision.get("executionBindings");
        assertThat(returnedBindings).extracting(IamS1ExecutionBinding::getValue).containsExactly("private-value");
        var inserted = org.mockito.ArgumentCaptor.forClass(QueryAttempt.class);
        verify(attempts).insert(inserted.capture());
        assertThat(inserted.getValue().getResourceRequestJson()).doesNotContain("private-value");
        assertThat(inserted.getValue().getExecutionSnapshotJson()).doesNotContain("private-value", "db-secret");
        assertThat(inserted.getValue().getSqlHash()).isEqualTo(sha256(sql));
        verify(tasks).updateById(task);
    }

    @Test
    void protectedResultStoresAndReturnsOnlyJavaMaskedRows() throws Exception {
        task.setIamResourceRequest("[{\"tableName\":\"users\",\"referencedColumns\":[\"phone\"]}]");
        String attemptId = "attempt-1";
        QueryAttempt attempt = QueryAttempt.builder().id(2L).taskId("task-1").attemptId(attemptId)
                .attemptNo(1).sqlHash("a".repeat(64)).status("EXECUTING")
                .permissionRevision(9L).activeMetadataSnapshotId(88L)
                .usedTables("[\"users\"]").usedColumns("[\"users.phone\"]")
                .resourceRequestJson("[]").executionSnapshotJson("{}").build();
        when(attempts.selectForUpdate("task-1", attemptId)).thenReturn(attempt);
        var field = new IamS1FieldProtectionVO(202L, "users", "phone", "MASKED", "PHONE", "masked");
        var table = new IamS1TablePermissionVO(true, "ALLOWED", "users", List.of("phone"), List.of(),
                List.of(field), List.of());
        var current = new IamS1DataAuthorizationSnapshot(true, "ALLOWED", "IAM-SIMPLE-1", 7L, 5L,
                "fixture", 88L, 9L, LocalDateTime.now(), null, List.of(table));
        when(queryService.recheck(task, 7L)).thenReturn(current);
        when(queryService.hasCompleteSourceTrace(any(), any())).thenReturn(true);
        when(queryService.deriveOutputMasks(any(), eq(current))).thenReturn(Map.of("phone", "PHONE"));
        when(masking.maskResultByFields(any(), eq(Map.of("phone", "PHONE"))))
                .thenReturn(List.of(Map.of("phone", "138****8000")));
        IamS1AttemptResultRequestDTO raw = new IamS1AttemptResultRequestDTO();
        raw.setAttemptId(attemptId);
        raw.setSqlHash("a".repeat(64));
        raw.setSuccess(true);
        raw.setData(List.of(Map.of("phone", "13800138000")));
        raw.setColumns(List.of(Map.of("name", "phone", "type", "VARCHAR")));
        raw.setUsedTables(List.of("users"));
        raw.setUsedColumns(List.of("users.phone"));
        raw.setSourceTrace(List.of(Map.of("outputColumn", "phone", "sources", List.of("users.phone"),
                "expression", "phone")));
        raw.setRowCount(1);
        raw.setExecutionTimeMs(12);

        Map<String, Object> result = service.protectResult("task-1", raw);

        assertThat(result.get("data").toString()).contains("138****8000").doesNotContain("13800138000");
        assertThat(attempt.getProtectedData()).contains("138****8000").doesNotContain("13800138000");
        assertThat(attempt.getSourceTrace()).doesNotContain("expression");
        assertThat(attempt.getStatus()).isEqualTo("PROTECTED");
    }

    @Test
    void protectedResultRejectsAnUntrackedRowKeyBeforeMaskingOrPersistence() throws Exception {
        task.setIamResourceRequest("[{\"tableName\":\"users\",\"referencedColumns\":[\"phone\"]}]");
        String attemptId = "attempt-untracked-key";
        QueryAttempt attempt = QueryAttempt.builder().id(3L).taskId("task-1").attemptId(attemptId)
                .attemptNo(1).sqlHash("b".repeat(64)).status("EXECUTING")
                .permissionRevision(9L).activeMetadataSnapshotId(88L)
                .usedTables("[\"users\"]").usedColumns("[\"users.phone\"]")
                .resourceRequestJson("[]").executionSnapshotJson("{}").build();
        when(attempts.selectForUpdate("task-1", attemptId)).thenReturn(attempt);
        var field = new IamS1FieldProtectionVO(202L, "users", "phone", "MASKED", "PHONE", "masked");
        var table = new IamS1TablePermissionVO(true, "ALLOWED", "users", List.of("phone"), List.of(),
                List.of(field), List.of());
        var current = new IamS1DataAuthorizationSnapshot(true, "ALLOWED", "IAM-SIMPLE-1", 7L, 5L,
                "fixture", 88L, 9L, LocalDateTime.now(), null, List.of(table));
        when(queryService.recheck(task, 7L)).thenReturn(current);
        when(queryService.hasCompleteSourceTrace(any(), any())).thenReturn(true);

        IamS1AttemptResultRequestDTO raw = new IamS1AttemptResultRequestDTO();
        raw.setAttemptId(attemptId);
        raw.setSqlHash("b".repeat(64));
        raw.setSuccess(true);
        raw.setData(List.of(Map.of("phone", "13800138000", "untracked", "UNTRACKED_RAW_VALUE")));
        raw.setColumns(List.of(Map.of("name", "phone", "type", "VARCHAR")));
        raw.setUsedTables(List.of("users"));
        raw.setUsedColumns(List.of("users.phone"));
        raw.setSourceTrace(List.of(Map.of("outputColumn", "phone", "sources", List.of("users.phone"),
                "expression", "phone")));
        raw.setRowCount(1);
        raw.setExecutionTimeMs(12);

        Map<String, Object> result = service.protectResult("task-1", raw);

        assertThat(result.get("allowed")).isEqualTo(false);
        assertThat(result.get("status")).isEqualTo("REJECTED");
        assertThat(mapper.writeValueAsString(result)).doesNotContain("UNTRACKED_RAW_VALUE");
        ArgumentCaptor<QueryAttempt> persisted = ArgumentCaptor.forClass(QueryAttempt.class);
        verify(attempts).updateById(persisted.capture());
        assertThat(mapper.writeValueAsString(persisted.getValue())).doesNotContain("UNTRACKED_RAW_VALUE");
        assertThat(persisted.getValue().getStatus()).isEqualTo("REJECTED");
        assertThat(persisted.getValue().getProtectedData()).isNull();
        assertThat(persisted.getValue().getProtectedColumns()).isNull();
        verify(masking, never()).maskResultByFields(any(), any());
        verify(queryService, never()).deriveOutputMasks(any(), any());
    }

    private IamS1AttemptAuthorizeRequestDTO authorizeRequest(String sql, String sqlHash) {
        IamS1AttemptAuthorizeRequestDTO request = new IamS1AttemptAuthorizeRequestDTO();
        request.setAttemptId("attempt-1");
        request.setSql(sql);
        request.setSqlHash(sqlHash);
        request.setUsedTables(List.of("orders"));
        request.setUsedColumns(List.of("orders.id"));
        IamS1AttemptColumnEvidenceDTO column = new IamS1AttemptColumnEvidenceDTO();
        column.setColumnName("id");
        column.setUsages(Set.of(IamS1ColumnUsage.PROJECTION));
        IamS1AttemptResourceEvidenceDTO resource = new IamS1AttemptResourceEvidenceDTO();
        resource.setTableName("orders");
        resource.setColumns(List.of(column));
        request.setResources(List.of(resource));
        return request;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new AssertionError(ex); }
    }
}
