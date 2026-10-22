package com.dataocean.module.fieldsemantic.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticActionDTO;
import com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticBindingBatchConfirmDTO;
import com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticDraftDTO;
import com.dataocean.module.fieldsemantic.entity.po.FieldSemanticCandidate;
import com.dataocean.module.fieldsemantic.entity.po.FieldSemanticBinding;
import com.dataocean.module.fieldsemantic.entity.po.FieldSemanticDefinition;
import com.dataocean.module.fieldsemantic.entity.po.FieldSemanticRevision;
import com.dataocean.module.fieldsemantic.mapper.FieldSemanticCandidateMapper;
import com.dataocean.module.fieldsemantic.mapper.FieldSemanticDefinitionMapper;
import com.dataocean.module.fieldsemantic.mapper.FieldSemanticReviewMapper;
import com.dataocean.module.fieldsemantic.mapper.FieldSemanticRevisionMapper;
import com.dataocean.module.fieldsemantic.mapper.FieldSemanticBindingMapper;
import com.dataocean.module.fieldsemantic.entity.vo.FieldSemanticPendingBindingVO;
import com.dataocean.module.fieldsemantic.mapper.SemanticCheckBatchMapper;
import com.dataocean.module.fieldsemantic.mapper.SemanticCheckResultMapper;
import com.dataocean.module.fieldsemantic.service.FieldSemanticService;
import com.dataocean.module.fieldsemantic.service.FieldSemanticBindingBatchService;
import com.dataocean.module.fieldsemantic.service.SemanticCheckService;
import com.dataocean.module.glossary.entity.po.GlossaryTerm;
import com.dataocean.module.glossary.entity.po.GlossaryTermReview;
import com.dataocean.module.glossary.entity.vo.TermColumnVO;
import com.dataocean.module.glossary.mapper.GlossaryTermMapper;
import com.dataocean.module.glossary.mapper.GlossaryTermReviewMapper;
import com.dataocean.module.glossary.service.GlossaryTermWorkflowService;
import com.dataocean.module.knowledge.client.PythonKnowledgeClient;
import com.dataocean.module.governance.entity.po.MetadataQualityIssue;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.governance.service.QualityIssueService;
import com.dataocean.module.versioning.service.impl.SnapshotLifecycleServiceImpl;
import com.dataocean.module.versioning.service.SnapshotAuditLogService;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.entity.po.TableRelation;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/** Real MyBatis persistence and Spring transaction-proxy coverage in an isolated H2 database. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:field_semantic_persistence;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always"
})
@ActiveProfiles("test")
@Sql(statements = {
        "DROP TABLE IF EXISTS field_semantic_candidate",
        "DROP TABLE IF EXISTS field_semantic_review",
        "DROP TABLE IF EXISTS field_semantic_revision",
        "DROP TABLE IF EXISTS field_semantic_binding",
        "DROP TABLE IF EXISTS field_semantic_definition",
        "DROP TABLE IF EXISTS semantic_check_result",
        "DROP TABLE IF EXISTS semantic_check_batch",
        "DROP TABLE IF EXISTS metadata_quality_issue",
        "DROP TABLE IF EXISTS db_column_meta",
        "DROP TABLE IF EXISTS db_table_meta",
        "DROP TABLE IF EXISTS table_relation",
        "DROP TABLE IF EXISTS metadata_snapshot",
        "DROP TABLE IF EXISTS datasource",
        "CREATE TABLE datasource (id BIGINT PRIMARY KEY, name VARCHAR(200), description VARCHAR(500), db_type VARCHAR(32), host VARCHAR(255), port INT, database_name VARCHAR(128), charset VARCHAR(64), status INT, health_status VARCHAR(32), creator_id BIGINT, deleted BIGINT DEFAULT 0, created_at TIMESTAMP, updated_at TIMESTAMP)",
        "CREATE TABLE metadata_snapshot (id BIGINT PRIMARY KEY, datasource_id BIGINT, snapshot_version INT, schema_hash VARCHAR(64), status VARCHAR(32), table_count INT, column_count INT, total_rows_estimate BIGINT, quality_score DECIMAL(5,2), sync_task_id BIGINT, reviewed_by BIGINT, reviewed_at TIMESTAMP, published_at TIMESTAMP, entity_sync_status VARCHAR(32), entity_sync_error VARCHAR(500), entity_synced_at TIMESTAMP, expired_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP)",
        "CREATE TABLE db_table_meta (id BIGINT PRIMARY KEY, snapshot_id BIGINT, datasource_id BIGINT, table_name VARCHAR(255), table_comment VARCHAR(500), table_type VARCHAR(32), engine VARCHAR(100), table_charset VARCHAR(100), row_count_estimate BIGINT, data_size_bytes BIGINT, index_size_bytes BIGINT, indexes_info CLOB, governance_status VARCHAR(32), created_at TIMESTAMP)",
        "CREATE TABLE db_column_meta (id BIGINT PRIMARY KEY, snapshot_id BIGINT, table_meta_id BIGINT, datasource_id BIGINT, table_name VARCHAR(255), column_name VARCHAR(255), column_comment VARCHAR(500), data_type VARCHAR(100), is_nullable INT, column_default VARCHAR(100), is_primary_key INT, ordinal_position INT, null_rate DECIMAL(8,6), distinct_count BIGINT, enum_top_values CLOB, min_value VARCHAR(500), max_value VARCHAR(500), governance_status VARCHAR(32), confidence_score INT, sample_values CLOB, created_at TIMESTAMP)",
        "CREATE TABLE table_relation (id BIGINT PRIMARY KEY, snapshot_id BIGINT, datasource_id BIGINT, source_table VARCHAR(255), source_column VARCHAR(255), target_table VARCHAR(255), target_column VARCHAR(255), relation_type VARCHAR(32), confidence DECIMAL(5,4), review_status VARCHAR(32), reviewed_by BIGINT, reviewed_at TIMESTAMP, created_at TIMESTAMP)",
        "CREATE TABLE field_semantic_definition (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, datasource_id BIGINT NOT NULL, database_name VARCHAR(128) NOT NULL, table_name VARCHAR(255) NOT NULL, column_name VARCHAR(255) NOT NULL, identity_hash CHAR(64) NOT NULL, current_binding_id BIGINT, current_approved_revision_id BIGINT, working_revision_id BIGINT, status VARCHAR(24), row_version INT, created_by BIGINT, updated_by BIGINT, created_at TIMESTAMP, updated_at TIMESTAMP, UNIQUE(datasource_id, identity_hash))",
        "CREATE TABLE field_semantic_binding (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, definition_id BIGINT NOT NULL, datasource_id BIGINT NOT NULL, snapshot_id BIGINT NOT NULL, table_meta_id BIGINT NOT NULL, column_meta_id BIGINT NOT NULL, table_name VARCHAR(255), column_name VARCHAR(255), schema_fingerprint CHAR(64), status VARCHAR(32), status_reason VARCHAR(1000), confirmation_evidence VARCHAR(1000), confirmed_by BIGINT, confirmed_at TIMESTAMP, created_by BIGINT, created_at TIMESTAMP, updated_at TIMESTAMP, UNIQUE(definition_id, snapshot_id))",
        "CREATE TABLE field_semantic_revision (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, definition_id BIGINT NOT NULL, binding_id BIGINT NOT NULL, revision_no INT NOT NULL, based_on_revision_id BIGINT, status VARCHAR(24), business_name VARCHAR(200), meaning CLOB, unit VARCHAR(200), currency VARCHAR(100), enum_semantics CLOB, metric_definition CLOB, applicability CLOB, evidence CLOB, unresolved_items_json CLOB, glossary_refs_json CLOB, content_hash CHAR(64), dependency_hash CHAR(64), created_by BIGINT, created_at TIMESTAMP, submitted_at TIMESTAMP, UNIQUE(definition_id, revision_no))",
        "CREATE TABLE field_semantic_review (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, definition_id BIGINT NOT NULL, revision_id BIGINT NOT NULL, binding_id BIGINT NOT NULL, action VARCHAR(32), expected_row_version INT, content_hash CHAR(64), dependency_hash CHAR(64), reason VARCHAR(1000), operator_id BIGINT, created_at TIMESTAMP)",
        "CREATE TABLE field_semantic_candidate (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, definition_id BIGINT NOT NULL, binding_id BIGINT NOT NULL, snapshot_id BIGINT NOT NULL, base_revision_id BIGINT, base_row_version INT, status VARCHAR(24), input_hash CHAR(64), input_json CLOB, business_name VARCHAR(200), meaning CLOB, unit VARCHAR(200), currency VARCHAR(100), enum_semantics CLOB, metric_definition CLOB, applicability CLOB, evidence CLOB, unresolved_items_json CLOB, failure_reason VARCHAR(1000), adopted_revision_id BIGINT, adopted_by BIGINT, adopted_at TIMESTAMP, created_by BIGINT, created_at TIMESTAMP, completed_at TIMESTAMP)",
        "CREATE TABLE semantic_check_batch (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, datasource_id BIGINT NOT NULL, snapshot_id BIGINT NOT NULL, status VARCHAR(24), applicable_count INT, valid_count INT, missing_count INT, stale_count INT, not_applicable_count INT, error_summary VARCHAR(1000), rule_version INT, started_at TIMESTAMP, finished_at TIMESTAMP, created_by BIGINT)",
        "CREATE TABLE semantic_check_result (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, batch_id BIGINT NOT NULL, definition_id BIGINT, binding_id BIGINT, column_meta_id BIGINT NOT NULL, table_name VARCHAR(255), column_name VARCHAR(255), rule_code VARCHAR(80), outcome VARCHAR(24), rule_version INT, revision_id BIGINT, review_id BIGINT, evidence_hash CHAR(64), reason VARCHAR(1000), issue_id BIGINT, created_at TIMESTAMP)",
        "CREATE TABLE metadata_quality_issue (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, snapshot_id BIGINT NOT NULL, datasource_id BIGINT NOT NULL, rule_id BIGINT, issue_domain VARCHAR(20) DEFAULT 'SOURCE', check_batch_id BIGINT, semantic_check_batch_id BIGINT, semantic_check_result_id BIGINT, semantic_rule_code VARCHAR(80), semantic_key VARCHAR(256), dimension VARCHAR(20), severity VARCHAR(10), table_name VARCHAR(200), column_name VARCHAR(200), issue_description VARCHAR(1000), suggestion VARCHAR(1000), status VARCHAR(20), assignee_id BIGINT, resolved_by BIGINT, resolved_at TIMESTAMP, resolution_note VARCHAR(500), created_at TIMESTAMP, updated_at TIMESTAMP, column_meta_id BIGINT)"
})
class FieldSemanticServicePersistenceTest {
    private static final Long DATASOURCE_ID = 5L;
    private static final Long SNAPSHOT_ID = 80L;
    private static final Long TABLE_ID = 90L;
    private static final Long COLUMN_ID = 91L;
    private static final Long OPERATOR_ID = 9L;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FieldSemanticService service;
    @Autowired private FieldSemanticBindingBatchService bindingBatchService;
    @Autowired private FieldSemanticDefinitionMapper definitionMapper;
    @Autowired private FieldSemanticRevisionMapper revisionMapper;
    @Autowired private FieldSemanticBindingMapper bindingMapper;
    @Autowired private FieldSemanticReviewMapper reviewMapper;
    @Autowired private FieldSemanticCandidateMapper candidateMapper;
    @Autowired private MetadataQualityIssueMapper issueMapper;
    @Autowired private MetadataSnapshotMapper snapshotMapper;
    @Autowired private SemanticCheckBatchMapper semanticBatchMapper;
    @Autowired private SemanticCheckResultMapper semanticResultMapper;
    @Autowired private SemanticCheckService semanticCheckService;
    @Autowired private QualityIssueService qualityIssueService;
    @Autowired private SnapshotLifecycleServiceImpl snapshotLifecycleService;

    @MockBean private IamS1AdminGuard adminGuard;
    @MockBean private IamS1CapabilityService capabilityService;
    @MockBean private PythonKnowledgeClient pythonKnowledgeClient;
    @MockBean private SnapshotAuditLogService snapshotAuditLogService;
    @MockBean private GlossaryTermMapper glossaryTermMapper;
    @MockBean private GlossaryTermReviewMapper glossaryTermReviewMapper;
    @MockBean private GlossaryTermWorkflowService glossaryWorkflow;

    @BeforeEach
    void seedSnapshot() {
        jdbc.update("INSERT INTO datasource(id,name,database_name,deleted) VALUES (?,?,?,0)", DATASOURCE_ID, "订单库", "orders_db");
        jdbc.update("INSERT INTO metadata_snapshot(id,datasource_id,snapshot_version,status,quality_score) VALUES (?,?,?,?,?)",
                SNAPSHOT_ID, DATASOURCE_ID, 1, "DRAFT", new java.math.BigDecimal("77.00"));
        jdbc.update("INSERT INTO db_table_meta(id,snapshot_id,datasource_id,table_name,table_comment,table_type,governance_status) VALUES (?,?,?,?,?,?,?)",
                TABLE_ID, SNAPSHOT_ID, DATASOURCE_ID, "orders", "订单表", "TABLE", "NORMAL");
        jdbc.update("INSERT INTO db_column_meta(id,snapshot_id,table_meta_id,datasource_id,table_name,column_name,column_comment,data_type,is_nullable,column_default,is_primary_key,ordinal_position,governance_status,confidence_score) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                COLUMN_ID, SNAPSHOT_ID, TABLE_ID, DATASOURCE_ID, "orders", "pay_amt", null, "DECIMAL(12,2)", 1, null, 0, 1, "NORMAL", 50);
        when(capabilityService.responsibleDatasourcesWithFunction(OPERATOR_ID, "glossary:view")).thenReturn(List.of());
    }

    @Test
    void draftSubmitApproveAndRevokeArePersistedAsSeparateVersionedEvidence() {
        var saved = service.saveDraft(COLUMN_ID, draft(0, "订单支付金额", "交易实际支付金额"), OPERATOR_ID);
        FieldSemanticDefinition definition = definitionMapper.selectById(saved.getDefinitionId());
        assertThat(definition.getRowVersion()).isEqualTo(2);
        FieldSemanticRevision draft = revisionMapper.selectById(saved.getWorkingRevisionId());
        assertThat(draft.getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getEffectiveStatus()).isEqualTo("NO_APPROVED_EXPLANATION");

        assertThatThrownBy(() -> service.saveDraft(COLUMN_ID, draft(1, "冲突名称", "不应覆盖"), OPERATOR_ID))
                .isInstanceOf(BusinessException.class);
        assertThat(revisionMapper.selectCount(null)).isEqualTo(1);

        var submitted = service.submit(COLUMN_ID, action(saved.getRowVersion(), draft.getId()), OPERATOR_ID);
        assertThat(revisionMapper.selectById(draft.getId()).getStatus()).isEqualTo("PENDING_REVIEW");
        assertThatThrownBy(() -> service.saveDraft(COLUMN_ID,
                draft(submitted.getRowVersion(), "越过审核", "不应修改固定提交"), OPERATOR_ID))
                .isInstanceOf(BusinessException.class);

        FieldSemanticActionDTO review = action(submitted.getRowVersion(), draft.getId());
        review.setAction("APPROVE");
        review.setReason("经业务口径文档核对，单位为人民币元。");
        var approved = service.review(COLUMN_ID, review, OPERATOR_ID);
        assertThat(approved.getEffectiveStatus()).isEqualTo("APPROVED");
        assertThat(approved.getCurrentApprovedRevisionId()).isEqualTo(draft.getId());
        assertThat(reviewMapper.selectCount(null)).isEqualTo(3);
        assertThat(reviewMapper.selectList(null).stream().map(item -> item.getAction()).toList())
                .contains("DRAFT_SAVED", "SUBMIT", "APPROVE");
        Map<String, Object> fact = service.knowledgeFacts(DATASOURCE_ID, SNAPSHOT_ID).get(0);
        Map<String, Object> marker = new java.util.LinkedHashMap<>(fact);
        marker.put("snapshotId", SNAPSHOT_ID); marker.put("factType", "FIELD_SEMANTIC");
        service.validateKnowledgeFact(DATASOURCE_ID, SNAPSHOT_ID, marker, String.valueOf(fact.get("body")));

        FieldSemanticActionDTO revoke = action(approved.getRowVersion(), draft.getId());
        revoke.setReason("业务口径文件已撤回。");
        var revoked = service.revoke(COLUMN_ID, revoke, OPERATOR_ID);
        assertThat(revoked.getEffectiveStatus()).isEqualTo("NEEDS_REVIEW");
        assertThat(revoked.getCurrentApprovedRevisionId()).isNull();
        assertThat(reviewMapper.selectCount(null)).isEqualTo(4);
        assertThat(revisionMapper.selectById(draft.getId()).getStatus()).isEqualTo("APPROVED");
        assertThat(service.knowledgeFacts(DATASOURCE_ID, SNAPSHOT_ID)).isEmpty();
        verify(adminGuard, atLeastOnce()).requireDatasourceFunction(OPERATOR_ID, "governance:field:manage", DATASOURCE_ID);
        verify(adminGuard, atLeastOnce()).requireDatasourceFunction(OPERATOR_ID, "knowledge:manage", DATASOURCE_ID);
        verify(adminGuard, atLeastOnce()).requireDatasourceFunction(OPERATOR_ID, "governance:field:view", DATASOURCE_ID);
        verify(adminGuard, atLeastOnce()).requireDatasourceFunction(OPERATOR_ID, "knowledge:approve", DATASOURCE_ID);
    }

    @Test
    void manualDraftRequiresBothManageFunctionsWithinTheRealDatasourceScope() {
        doThrow(new BusinessException(403, "当前源未授予 knowledge:manage"))
                .when(adminGuard).requireDatasourceFunction(OPERATOR_ID, "knowledge:manage", DATASOURCE_ID);

        assertThatThrownBy(() -> service.saveDraft(COLUMN_ID, draft(0, "无权限解释", "不得落库"), OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("knowledge:manage");

        verify(adminGuard).requireDatasourceFunction(OPERATOR_ID, "governance:field:manage", DATASOURCE_ID);
        verify(adminGuard).requireDatasourceFunction(OPERATOR_ID, "knowledge:manage", DATASOURCE_ID);
        assertThat(definitionMapper.selectCount(null)).isZero();
        assertThat(revisionMapper.selectCount(null)).isZero();
    }

    @Test
    void expiredAiResponseCannotReplaceManualDraftAndIsRetainedAsStaleCandidate() {
        var saved = service.saveDraft(COLUMN_ID, draft(0, "人工解释", "人工填写内容保留"), OPERATOR_ID);
        FieldSemanticDefinition definition = definitionMapper.selectById(saved.getDefinitionId());
        when(pythonKnowledgeClient.generateFieldSemanticCandidate(anyMap())).thenAnswer(invocation -> {
            definitionMapper.update(null, new LambdaUpdateWrapper<FieldSemanticDefinition>()
                    .eq(FieldSemanticDefinition::getId, definition.getId())
                    .eq(FieldSemanticDefinition::getRowVersion, definition.getRowVersion())
                    .set(FieldSemanticDefinition::getRowVersion, definition.getRowVersion() + 1));
            return Map.of("businessName", "过期建议", "meaning", "过期响应", "evidence", "未审核建议",
                    "unit", "", "currency", "", "enumSemantics", "", "metricDefinition", "",
                    "applicability", "", "unresolvedItems", List.of("待人工确认"));
        });

        assertThatThrownBy(() -> service.generateCandidate(COLUMN_ID, SNAPSHOT_ID, saved.getRowVersion(), OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 响应已过期");
        FieldSemanticRevision persistedDraft = revisionMapper.selectById(saved.getWorkingRevisionId());
        assertThat(persistedDraft.getBusinessName()).isEqualTo("人工解释");
        assertThat(persistedDraft.getMeaning()).isEqualTo("人工填写内容保留");
        FieldSemanticCandidate candidate = candidateMapper.selectOne(null);
        assertThat(candidate.getStatus()).isEqualTo("STALE");
        assertThat(candidate.getFailureReason()).contains("不能采纳");
    }

    @Test
    void candidatePayloadExcludesBlockedAndDeprecatedFieldsFromEveryContextChannel() {
        insertSiblingColumn(95L, "normal_total", "正常字段注释", "NORMAL", 2);
        insertSiblingColumn(96L, "blocked_secret", "BLOCKED_COMMENT_MUST_NOT_REACH_MODEL", "BLOCKED", 3);
        insertSiblingColumn(97L, "deprecated_legacy", "DEPRECATED_COMMENT_MUST_NOT_REACH_MODEL", "DEPRECATED", 4);
        insertSiblingColumn(98L, "recommended_metric", "推荐字段注释", "RECOMMENDED", 5);
        insertSiblingColumn(99L, "discovered_metric", "待发现字段注释", "DISCOVERED", 6);
        insertRelation(301L, "normal_total", "MANUAL");
        insertRelation(302L, "blocked_secret", "FK");
        insertRelation(303L, "deprecated_legacy", "FK");
        stubGlossaryWithRestrictedDependencies();
        AtomicReference<Map<String, Object>> sentInput = new AtomicReference<>();
        when(pythonKnowledgeClient.generateFieldSemanticCandidate(anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> input = invocation.getArgument(0);
            sentInput.set(input);
            return candidateResponse();
        });

        service.generateCandidate(COLUMN_ID, SNAPSHOT_ID, 0, OPERATOR_ID);

        Map<String, Object> input = sentInput.get();
        assertThat(input).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sameTableColumns = (List<Map<String, Object>>) input.get("sameTableColumns");
        assertThat(sameTableColumns).extracting(column -> column.get("columnName"))
                .contains("pay_amt", "normal_total", "recommended_metric", "discovered_metric")
                .doesNotContain("blocked_secret", "deprecated_legacy");
        assertThat(sameTableColumns).extracting(column -> column.get("rawCommentPendingConfirmation"))
                .contains("正常字段注释", "推荐字段注释", "待发现字段注释")
                .doesNotContain("BLOCKED_COMMENT_MUST_NOT_REACH_MODEL", "DEPRECATED_COMMENT_MUST_NOT_REACH_MODEL");
        assertThat(input.get("compliantRelations").toString()).contains("normal_total")
                .doesNotContain("blocked_secret", "deprecated_legacy");
        assertThat(input.get("reviewedGlossary")).as("术语映射含有受限字段时应整条排除，不能裁剪依赖后发送")
                .asList().isEmpty();
        assertThat(input.toString()).doesNotContain("blocked_secret", "deprecated_legacy",
                "BLOCKED_COMMENT_MUST_NOT_REACH_MODEL", "DEPRECATED_COMMENT_MUST_NOT_REACH_MODEL",
                "含受限字段的术语");
        assertThat(candidateMapper.selectOne(null).getStatus()).isEqualTo("READY");
        assertThatThrownBy(() -> service.generateCandidate(COLUMN_ID, SNAPSHOT_ID, 0, OPERATOR_ID))
                .isInstanceOf(BusinessException.class).hasMessageContaining("版本已变化");
        verify(pythonKnowledgeClient, times(1)).generateFieldSemanticCandidate(anyMap());
    }

    @Test
    void blockedOrDeprecatedTargetFieldIsRejectedBeforePythonIsCalled() {
        for (String status : List.of("BLOCKED", "DEPRECATED")) {
            jdbc.update("UPDATE db_column_meta SET governance_status=? WHERE id=?", status, COLUMN_ID);
            assertThatThrownBy(() -> service.generateCandidate(COLUMN_ID, SNAPSHOT_ID, 0, OPERATOR_ID))
                    .as("目标字段状态 %s 必须在调用 Python 前拒绝", status)
                    .isInstanceOf(BusinessException.class).hasMessageContaining("BLOCKED/DEPRECATED");
        }
        verify(pythonKnowledgeClient, never()).generateFieldSemanticCandidate(anyMap());
        assertThat(candidateMapper.selectCount(null)).isZero();
        assertThat(definitionMapper.selectCount(null)).isZero();
    }

    @Test
    void recollectionCreatesPendingBindingAndNeverExtendsOldApprovalAutomatically() {
        var saved = service.saveDraft(COLUMN_ID, draft(0, "订单支付金额", "人民币支付金额"), OPERATOR_ID);
        var submitted = service.submit(COLUMN_ID, action(saved.getRowVersion(), saved.getWorkingRevisionId()), OPERATOR_ID);
        FieldSemanticActionDTO approval = action(submitted.getRowVersion(), submitted.getWorkingRevisionId());
        approval.setAction("APPROVE"); approval.setReason("业务术语已核对");
        var approved = service.review(COLUMN_ID, approval, OPERATOR_ID);
        jdbc.update("INSERT INTO metadata_snapshot(id,datasource_id,snapshot_version,status) VALUES (?,?,?,?)",
                81L, DATASOURCE_ID, 2, "DRAFT");
        jdbc.update("INSERT INTO db_table_meta(id,snapshot_id,datasource_id,table_name,table_comment,table_type,governance_status) VALUES (?,?,?,?,?,?,?)",
                92L, 81L, DATASOURCE_ID, "orders", "订单表", "TABLE", "NORMAL");
        jdbc.update("INSERT INTO db_column_meta(id,snapshot_id,table_meta_id,datasource_id,table_name,column_name,column_comment,data_type,is_nullable,column_default,is_primary_key,ordinal_position,governance_status,confidence_score) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                93L, 81L, 92L, DATASOURCE_ID, "orders", "pay_amt", null, "DECIMAL(12,2)", 1, null, 0, 1, "NORMAL", 50);

        service.bindCollectedSnapshot(DATASOURCE_ID, 81L, OPERATOR_ID);
        var newSnapshot = service.workspace(93L, 81L, OPERATOR_ID);
        assertThat(newSnapshot.getBindingStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(newSnapshot.getEffectiveStatus()).isEqualTo("NEEDS_REVIEW");
        assertThat(newSnapshot.getCurrentApprovedRevisionId()).isEqualTo(approved.getCurrentApprovedRevisionId());

        var oldRevision = revisionMapper.selectById(approved.getCurrentApprovedRevisionId());
        var oldBinding = bindingMapper.selectById(approved.getCurrentBindingId());
        var oldReview = reviewMapper.selectList(null).stream().filter(item -> "APPROVE".equals(item.getAction())).findFirst().orElseThrow();
        assertThat(service.membershipValid(DATASOURCE_ID, SNAPSHOT_ID,
                List.of(oldRevision.getDefinitionId().toString(), oldRevision.getId().toString(),
                        oldReview.getId().toString(), oldBinding.getId().toString())))
                .isTrue();

        var confirmed = service.confirmBinding(93L, 81L,
                bindingConfirm(newSnapshot.getRowVersion(), "新快照字段结构与原来源逐项核对一致"), OPERATOR_ID);
        assertThat(confirmed.getEffectiveStatus()).isEqualTo("APPROVED");
        assertThat(confirmed.getCurrentApprovedRevisionId()).isEqualTo(oldRevision.getId());
        assertThat(reviewMapper.selectCount(null)).isEqualTo(4);
        assertThat(reviewMapper.selectList(null).stream().map(item -> item.getAction()).toList())
                .contains("DRAFT_SAVED", "SUBMIT", "APPROVE", "BIND_CONFIRM");
        assertThat(service.membershipValid(DATASOURCE_ID, SNAPSHOT_ID,
                List.of(oldRevision.getDefinitionId().toString(), oldRevision.getId().toString(),
                        oldReview.getId().toString(), oldBinding.getId().toString())))
                .isFalse();
        var currentBinding = bindingMapper.selectById(confirmed.getCurrentBindingId());
        var newReview = reviewMapper.selectList(null).stream().filter(item -> "BIND_CONFIRM".equals(item.getAction())).findFirst().orElseThrow();
        assertThat(service.membershipValid(DATASOURCE_ID, 81L,
                List.of(oldRevision.getDefinitionId().toString(), oldRevision.getId().toString(),
                        newReview.getId().toString(), currentBinding.getId().toString())))
                .isTrue();
    }

    @Test
    void batchBindingConfirmationPreauthorizesAllItemsAndReportsPartialBusinessFailure() {
        var saved = service.saveDraft(COLUMN_ID, draft(0, "订单支付金额", "人民币支付金额"), OPERATOR_ID);
        var submitted = service.submit(COLUMN_ID, action(saved.getRowVersion(), saved.getWorkingRevisionId()), OPERATOR_ID);
        FieldSemanticActionDTO approval = action(submitted.getRowVersion(), submitted.getWorkingRevisionId());
        approval.setAction("APPROVE"); approval.setReason("依据已核实");
        service.review(COLUMN_ID, approval, OPERATOR_ID);
        jdbc.update("INSERT INTO metadata_snapshot(id,datasource_id,snapshot_version,status) VALUES (?,?,?,?)",
                81L, DATASOURCE_ID, 2, "DRAFT");
        jdbc.update("INSERT INTO db_table_meta(id,snapshot_id,datasource_id,table_name,table_comment,table_type,governance_status) VALUES (?,?,?,?,?,?,?)",
                92L, 81L, DATASOURCE_ID, "orders", "订单表", "TABLE", "NORMAL");
        jdbc.update("INSERT INTO db_column_meta(id,snapshot_id,table_meta_id,datasource_id,table_name,column_name,column_comment,data_type,is_nullable,column_default,is_primary_key,ordinal_position,governance_status,confidence_score) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                93L, 81L, 92L, DATASOURCE_ID, "orders", "pay_amt", null, "DECIMAL(12,2)", 1, null, 0, 1, "NORMAL", 50);
        jdbc.update("INSERT INTO db_column_meta(id,snapshot_id,table_meta_id,datasource_id,table_name,column_name,column_comment,data_type,is_nullable,column_default,is_primary_key,ordinal_position,governance_status,confidence_score) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                94L, 81L, 92L, DATASOURCE_ID, "orders", "unknown_amt", null, "DECIMAL(12,2)", 1, null, 0, 2, "NORMAL", 50);
        service.bindCollectedSnapshot(DATASOURCE_ID, 81L, OPERATOR_ID);
        assertThat(bindingBatchService.pending(81L, OPERATOR_ID))
                .filteredOn(FieldSemanticPendingBindingVO::isEligible)
                .extracting(FieldSemanticPendingBindingVO::getColumnMetaId).containsExactly(93L);
        var pending = service.workspace(93L, 81L, OPERATOR_ID);

        FieldSemanticBindingBatchConfirmDTO request = new FieldSemanticBindingBatchConfirmDTO();
        FieldSemanticBindingBatchConfirmDTO.Item eligible = new FieldSemanticBindingBatchConfirmDTO.Item();
        eligible.setColumnMetaId(93L); eligible.setExpectedVersion(pending.getRowVersion()); eligible.setEvidence("逐项核对结构及业务口径一致");
        FieldSemanticBindingBatchConfirmDTO.Item missingDefinition = new FieldSemanticBindingBatchConfirmDTO.Item();
        missingDefinition.setColumnMetaId(94L); missingDefinition.setExpectedVersion(0); missingDefinition.setEvidence("复核后发现没有可沿用的解释");
        request.setItems(List.of(eligible, missingDefinition));

        var result = bindingBatchService.confirm(81L, request, OPERATOR_ID);
        assertThat(result.getTotalCount()).isEqualTo(2);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getItems()).extracting("success").containsExactly(true, false);
        assertThat(service.workspace(93L, 81L, OPERATOR_ID).getEffectiveStatus()).isEqualTo("APPROVED");
        assertThat(reviewMapper.selectList(null).stream().map(item -> item.getAction()).toList()).contains("BIND_CONFIRM");
    }

    @Test
    void changedRecollectedBindingCommitsNeedsReviewStateWhenConfirmationConflicts() {
        var saved = service.saveDraft(COLUMN_ID, draft(0, "订单支付金额", "人民币支付金额"), OPERATOR_ID);
        var submitted = service.submit(COLUMN_ID, action(saved.getRowVersion(), saved.getWorkingRevisionId()), OPERATOR_ID);
        FieldSemanticActionDTO approval = action(submitted.getRowVersion(), submitted.getWorkingRevisionId());
        approval.setAction("APPROVE"); approval.setReason("原来源依据已核实");
        service.review(COLUMN_ID, approval, OPERATOR_ID);
        jdbc.update("INSERT INTO metadata_snapshot(id,datasource_id,snapshot_version,status) VALUES (?,?,?,?)",
                81L, DATASOURCE_ID, 2, "DRAFT");
        jdbc.update("INSERT INTO db_table_meta(id,snapshot_id,datasource_id,table_name,table_comment,table_type,governance_status) VALUES (?,?,?,?,?,?,?)",
                92L, 81L, DATASOURCE_ID, "orders", "订单表", "TABLE", "NORMAL");
        jdbc.update("INSERT INTO db_column_meta(id,snapshot_id,table_meta_id,datasource_id,table_name,column_name,column_comment,data_type,is_nullable,column_default,is_primary_key,ordinal_position,governance_status,confidence_score) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                93L, 81L, 92L, DATASOURCE_ID, "orders", "pay_amt", null, "VARCHAR(120)", 1, null, 0, 1, "NORMAL", 50);
        service.bindCollectedSnapshot(DATASOURCE_ID, 81L, OPERATOR_ID);
        var pending = service.workspace(93L, 81L, OPERATOR_ID);
        assertThat(bindingBatchService.pending(81L, OPERATOR_ID)).singleElement()
                .satisfies(item -> {
                    assertThat(item.isEligible()).isFalse();
                    assertThat(item.getReason()).contains("结构或完整依据已变化");
                });

        assertThatThrownBy(() -> service.confirmBinding(93L, 81L,
                bindingConfirm(pending.getRowVersion(), "尝试沿用旧解释"), OPERATOR_ID))
                .isInstanceOf(BusinessException.class);
        assertThat(bindingMapper.selectOne(new LambdaQueryWrapper<FieldSemanticBinding>()
                .eq(FieldSemanticBinding::getSnapshotId, 81L)))
                .extracting("status").isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void twoConcurrentEditorsUsingOneExpectedVersionCommitOnlyOneRevision() throws Exception {
        var saved = service.saveDraft(COLUMN_ID, draft(0, "原始名称", "原始含义"), OPERATOR_ID);
        int expectedVersion = saved.getRowVersion();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> concurrentSave(ready, start, expectedVersion, "并发名称甲"));
            Future<String> second = executor.submit(() -> concurrentSave(ready, start, expectedVersion, "并发名称乙"));
            ready.await();
            start.countDown();
            List<String> results = List.of(first.get(), second.get());
            assertThat(results).containsExactlyInAnyOrder("SAVED", "CONFLICT");
            assertThat(revisionMapper.selectCount(null)).isEqualTo(2);
            FieldSemanticDefinition current = definitionMapper.selectById(saved.getDefinitionId());
            assertThat(revisionMapper.selectById(current.getWorkingRevisionId()).getStatus()).isEqualTo("DRAFT");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void semanticChecksStayOutOfSourceDomainAndRequireCurrentEvidenceToResolve() {
        MetadataQualityIssue sourceIssue = new MetadataQualityIssue();
        sourceIssue.setSnapshotId(SNAPSHOT_ID); sourceIssue.setDatasourceId(DATASOURCE_ID);
        sourceIssue.setRuleId(1L); sourceIssue.setIssueDomain(MetadataQualityIssue.DOMAIN_SOURCE);
        sourceIssue.setDimension("COMPLETENESS"); sourceIssue.setSeverity("HIGH");
        sourceIssue.setTableName("orders"); sourceIssue.setColumnName("pay_amt");
        sourceIssue.setIssueDescription("原始字段注释缺失"); sourceIssue.setStatus(MetadataQualityIssue.STATUS_OPEN);
        issueMapper.insert(sourceIssue);

        var missingBatch = semanticCheckService.run(SNAPSHOT_ID, OPERATOR_ID);
        assertThat(missingBatch.getStatus()).isEqualTo("SUCCESS");
        assertThat(missingBatch.getMissingCount()).isEqualTo(1);
        MetadataQualityIssue semanticIssue = issueMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MetadataQualityIssue>()
                .eq(MetadataQualityIssue::getIssueDomain, MetadataQualityIssue.DOMAIN_SEMANTIC)
                .eq(MetadataQualityIssue::getSemanticKey, SNAPSHOT_ID + ":" + COLUMN_ID));
        assertThat(semanticIssue).isNotNull();
        assertThat(semanticIssue.getIssueDomain()).isEqualTo(MetadataQualityIssue.DOMAIN_SEMANTIC);
        assertThat(issueMapper.selectById(sourceIssue.getId()).getStatus()).isEqualTo(MetadataQualityIssue.STATUS_OPEN);
        assertThat(snapshotMapper.selectById(SNAPSHOT_ID).getStatus()).isEqualTo("DRAFT");
        assertThat(snapshotMapper.selectById(SNAPSHOT_ID).getQualityScore()).isEqualByComparingTo("77.00");

        var saved = service.saveDraft(COLUMN_ID, draft(0, "订单支付金额", "订单记录的实付金额"), OPERATOR_ID);
        var submitted = service.submit(COLUMN_ID, action(saved.getRowVersion(), saved.getWorkingRevisionId()), OPERATOR_ID);
        FieldSemanticActionDTO approval = action(submitted.getRowVersion(), submitted.getWorkingRevisionId());
        approval.setAction("APPROVE"); approval.setReason("已核对业务定义");
        service.review(COLUMN_ID, approval, OPERATOR_ID);
        var validBatch = semanticCheckService.run(SNAPSHOT_ID, OPERATOR_ID);
        assertThat(validBatch.getStatus()).isEqualTo("SUCCESS");
        assertThat(validBatch.getValidCount()).isEqualTo(1);
        assertThat(issueMapper.selectById(semanticIssue.getId()).getStatus()).isEqualTo(MetadataQualityIssue.STATUS_OPEN);

        qualityIssueService.handleIssue(semanticIssue.getId(), MetadataQualityIssue.STATUS_CONFIRMED, null, OPERATOR_ID);
        qualityIssueService.handleIssue(semanticIssue.getId(), MetadataQualityIssue.STATUS_RESOLVED, "最新复查确认当前修订有效", OPERATOR_ID);
        assertThat(issueMapper.selectById(semanticIssue.getId()).getStatus()).isEqualTo(MetadataQualityIssue.STATUS_RESOLVED);

        var current = service.workspace(COLUMN_ID, SNAPSHOT_ID, OPERATOR_ID);
        FieldSemanticActionDTO revoke = action(current.getRowVersion(), current.getCurrentApprovedRevisionId());
        revoke.setReason("当前口径依据失效");
        service.revoke(COLUMN_ID, revoke, OPERATOR_ID);
        semanticCheckService.run(SNAPSHOT_ID, OPERATOR_ID);
        semanticIssue = issueMapper.selectById(semanticIssue.getId());
        assertThat(semanticIssue.getStatus()).isEqualTo(MetadataQualityIssue.STATUS_REOPENED);
        qualityIssueService.handleIssue(semanticIssue.getId(), MetadataQualityIssue.STATUS_CONFIRMED, null, OPERATOR_ID);
        MetadataQualityIssue unresolved = semanticIssue;
        assertThatThrownBy(() -> qualityIssueService.handleIssue(unresolved.getId(), MetadataQualityIssue.STATUS_RESOLVED,
                "依据已经变化仍尝试关闭", OPERATOR_ID)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("复查证据");
        assertThat(issueMapper.selectById(sourceIssue.getId()).getStatus()).isEqualTo(MetadataQualityIssue.STATUS_OPEN);
        assertThat(semanticBatchMapper.selectCount(null)).isEqualTo(3);
        assertThat(semanticResultMapper.selectCount(null)).isEqualTo(3);
    }

    @Test
    void highSemanticIssueDoesNotBlockSnapshotApprovalButHighSourceIssueStillDoes() {
        jdbc.update("UPDATE metadata_snapshot SET status='ISSUE_FOUND' WHERE id=?", SNAPSHOT_ID);
        MetadataQualityIssue semanticHigh = new MetadataQualityIssue();
        semanticHigh.setSnapshotId(SNAPSHOT_ID); semanticHigh.setDatasourceId(DATASOURCE_ID);
        semanticHigh.setIssueDomain(MetadataQualityIssue.DOMAIN_SEMANTIC); semanticHigh.setSemanticKey("80:91");
        semanticHigh.setSemanticRuleCode("STALE_FIELD_SEMANTIC"); semanticHigh.setDimension("SEMANTIC");
        semanticHigh.setSeverity("HIGH"); semanticHigh.setTableName("orders"); semanticHigh.setColumnName("pay_amt");
        semanticHigh.setIssueDescription("测试：语义高优先级不进入来源发布门禁"); semanticHigh.setStatus(MetadataQualityIssue.STATUS_OPEN);
        issueMapper.insert(semanticHigh);

        snapshotLifecycleService.changeStatus(SNAPSHOT_ID, "APPROVED", OPERATOR_ID, "来源质量复核通过");
        assertThat(snapshotMapper.selectById(SNAPSHOT_ID).getStatus()).isEqualTo("APPROVED");

        jdbc.update("UPDATE metadata_snapshot SET status='ISSUE_FOUND' WHERE id=?", SNAPSHOT_ID);
        MetadataQualityIssue sourceHigh = new MetadataQualityIssue();
        sourceHigh.setSnapshotId(SNAPSHOT_ID); sourceHigh.setDatasourceId(DATASOURCE_ID);
        sourceHigh.setRuleId(1L); sourceHigh.setIssueDomain(MetadataQualityIssue.DOMAIN_SOURCE);
        sourceHigh.setDimension("COMPLETENESS"); sourceHigh.setSeverity("HIGH");
        sourceHigh.setTableName("orders"); sourceHigh.setColumnName("pay_amt");
        sourceHigh.setIssueDescription("来源质量高风险"); sourceHigh.setStatus(MetadataQualityIssue.STATUS_OPEN);
        issueMapper.insert(sourceHigh);
        assertThatThrownBy(() -> snapshotLifecycleService.changeStatus(SNAPSHOT_ID, "APPROVED", OPERATOR_ID, "复核"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("高风险问题");
        assertThat(snapshotMapper.selectById(SNAPSHOT_ID).getStatus()).isEqualTo("ISSUE_FOUND");

        // REOPENED 同属未解决：来源高风险被重新打开后仍必须阻断审批。
        jdbc.update("UPDATE metadata_quality_issue SET status='RESOLVED' WHERE id=?", sourceHigh.getId());
        MetadataQualityIssue reopenedSource = new MetadataQualityIssue();
        reopenedSource.setSnapshotId(SNAPSHOT_ID); reopenedSource.setDatasourceId(DATASOURCE_ID);
        reopenedSource.setRuleId(1L); reopenedSource.setIssueDomain(MetadataQualityIssue.DOMAIN_SOURCE);
        reopenedSource.setDimension("COMPLETENESS"); reopenedSource.setSeverity("HIGH");
        reopenedSource.setTableName("orders"); reopenedSource.setColumnName("pay_amt");
        reopenedSource.setIssueDescription("来源质量高风险（重新打开）");
        reopenedSource.setStatus(MetadataQualityIssue.STATUS_REOPENED);
        issueMapper.insert(reopenedSource);
        assertThatThrownBy(() -> snapshotLifecycleService.changeStatus(SNAPSHOT_ID, "APPROVED", OPERATOR_ID, "复核"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("高风险问题");
        assertThat(snapshotMapper.selectById(SNAPSHOT_ID).getStatus()).isEqualTo("ISSUE_FOUND");
    }

    private FieldSemanticDraftDTO draft(int expectedVersion, String businessName, String meaning) {
        FieldSemanticDraftDTO dto = new FieldSemanticDraftDTO();
        dto.setSnapshotId(SNAPSHOT_ID); dto.setExpectedVersion(expectedVersion);
        dto.setBusinessName(businessName); dto.setMeaning(meaning); dto.setEvidence("订单业务定义文档");
        dto.setUnresolvedItems(List.of("退款是否计入待确认"));
        return dto;
    }

    private FieldSemanticActionDTO action(int expectedVersion, Long revisionId) {
        FieldSemanticActionDTO dto = new FieldSemanticActionDTO();
        dto.setExpectedVersion(expectedVersion); dto.setRevisionId(revisionId);
        return dto;
    }

    private com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticBindingConfirmDTO bindingConfirm(
            int expectedVersion, String evidence) {
        var dto = new com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticBindingConfirmDTO();
        dto.setExpectedVersion(expectedVersion); dto.setEvidence(evidence); return dto;
    }

    private void insertSiblingColumn(Long id, String name, String comment, String status, int ordinal) {
        jdbc.update("INSERT INTO db_column_meta(id,snapshot_id,table_meta_id,datasource_id,table_name,column_name,column_comment,data_type,is_nullable,column_default,is_primary_key,ordinal_position,governance_status,confidence_score) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, SNAPSHOT_ID, TABLE_ID, DATASOURCE_ID, "orders", name, comment, "DECIMAL(12,2)", 1, null, 0, ordinal, status, 50);
    }

    private void insertRelation(Long id, String targetColumn, String relationType) {
        jdbc.update("INSERT INTO table_relation(id,snapshot_id,datasource_id,source_table,source_column,target_table,target_column,relation_type,review_status) VALUES (?,?,?,?,?,?,?,?,?)",
                id, SNAPSHOT_ID, DATASOURCE_ID, "orders", "pay_amt", "orders", targetColumn, relationType,
                TableRelation.TYPE_MANUAL.equals(relationType) ? "CONFIRMED" : null);
    }

    private void stubGlossaryWithRestrictedDependencies() {
        GlossaryTerm term = new GlossaryTerm();
        term.setId(100L); term.setStatus(GlossaryTerm.STATUS_APPROVED); term.setName("订单解释词");
        GlossaryTermReview review = new GlossaryTermReview();
        review.setId(700L); review.setTermId(term.getId()); review.setRevision(3L);
        review.setAction("APPROVED"); review.setContentHash("approved-glossary-hash");
        List<TermColumnVO> columns = List.of(
                new TermColumnVO(1L, DATASOURCE_ID, "订单库", SNAPSHOT_ID, TABLE_ID, COLUMN_ID,
                        "orders", "pay_amt", "DECIMAL(12,2)", null, "orders.pay_amt"),
                new TermColumnVO(1L, DATASOURCE_ID, "订单库", SNAPSHOT_ID, TABLE_ID, 96L,
                        "orders", "blocked_secret", "DECIMAL(12,2)", null, "orders.blocked_secret"),
                new TermColumnVO(1L, DATASOURCE_ID, "订单库", SNAPSHOT_ID, TABLE_ID, 97L,
                        "orders", "deprecated_legacy", "DECIMAL(12,2)", null, "orders.deprecated_legacy"));
        Map<String, Object> fact = Map.of("reviewId", review.getId(), "revision", review.getRevision(),
                "contentHash", review.getContentHash(), "displayName", "含受限字段的术语",
                "description", "完整映射依据", "synonyms", "", "columns", columns);
        when(capabilityService.responsibleDatasourcesWithFunction(OPERATOR_ID, "glossary:view"))
                .thenReturn(List.of(new IamS1DatasourceRefVO(DATASOURCE_ID, "订单库", true)));
        when(glossaryTermMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(term));
        when(glossaryTermReviewMapper.selectById(review.getId())).thenReturn(review);
        when(glossaryWorkflow.approvedFact(term.getId())).thenReturn(fact);
    }

    private Map<String, Object> candidateResponse() {
        return Map.of("businessName", "订单支付金额", "meaning", "订单记录的实际支付金额",
                "evidence", "只依据允许的元数据", "unit", "", "currency", "",
                "enumSemantics", "", "metricDefinition", "", "applicability", "",
                "unresolvedItems", List.of());
    }

    private String concurrentSave(CountDownLatch ready, CountDownLatch start, int expectedVersion, String name) {
        ready.countDown();
        try {
            start.await();
            service.saveDraft(COLUMN_ID, draft(expectedVersion, name, "并发编辑内容"), OPERATOR_ID);
            return "SAVED";
        } catch (BusinessException exception) {
            return "CONFLICT";
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
