package com.dataocean.module.fieldsemantic.service.impl;

import com.dataocean.module.fieldsemantic.entity.po.SemanticCheckBatch;
import com.dataocean.module.fieldsemantic.entity.po.SemanticCheckResult;
import com.dataocean.module.fieldsemantic.entity.vo.FieldSemanticWorkspaceVO;
import com.dataocean.module.fieldsemantic.mapper.SemanticCheckBatchMapper;
import com.dataocean.module.fieldsemantic.mapper.SemanticCheckResultMapper;
import com.dataocean.module.fieldsemantic.service.FieldSemanticEvaluation;
import com.dataocean.module.fieldsemantic.service.FieldSemanticService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class FieldGovernanceProgressServiceTest {
    private final FieldSemanticService semantics = mock(FieldSemanticService.class);
    private final SemanticCheckResultMapper results = mock(SemanticCheckResultMapper.class);
    private final SemanticCheckBatchMapper batches = mock(SemanticCheckBatchMapper.class);
    private final com.dataocean.module.fieldtag.mapper.FieldTagMapper tags=mock(com.dataocean.module.fieldtag.mapper.FieldTagMapper.class);
    private final FieldGovernanceProgressServiceImpl service = new FieldGovernanceProgressServiceImpl(semantics, results, batches,tags);

    private FieldSemanticWorkspaceVO approved() {
        when(tags.selectCount(any())).thenReturn(1L);
        var workspace = new FieldSemanticWorkspaceVO(); workspace.setDatasourceId(7L);
        when(semantics.workspace(1L, 2L, 3L)).thenReturn(workspace);
        when(semantics.evaluateForGovernance(1L, 2L)).thenReturn(new FieldSemanticEvaluation(
                "VALID", "OK", "有效", 4L, 5L, 6L, 8L, "content", "dependency", "evidence"));
        return workspace;
    }
    private SemanticCheckResult proof() {
        var result = new SemanticCheckResult(); result.setBatchId(9L); result.setDefinitionId(4L);
        result.setColumnMetaId(1L); result.setBindingId(5L);
        result.setRevisionId(6L); result.setReviewId(8L); result.setEvidenceHash("evidence");
        result.setOutcome("VALID"); result.setRuleVersion(SemanticCheckServiceImpl.RULE_VERSION);
        when(results.selectOne(any())).thenReturn(result);
        var batch = new SemanticCheckBatch(); batch.setId(9L); batch.setDatasourceId(7L);
        batch.setSnapshotId(2L); batch.setStatus("SUCCESS"); batch.setRuleVersion(result.getRuleVersion());
        when(batches.selectById(9L)).thenReturn(batch);
        return result;
    }
    @Test void approvalAloneDoesNotCompleteRound() {
        approved(); assertThat(service.progress(1L, 2L, 3L).stage()).isEqualTo("AWAITING_RECHECK");
    }
    @Test void missingClassificationDoesNotAppearAsCompletedGovernance() {
        approved();proof();when(tags.selectCount(any())).thenReturn(0L);
        assertThat(service.progress(1L,2L,3L).stage()).isEqualTo("NEEDS_CLASSIFICATION");
    }
    @Test void exactSuccessfulEvidenceCompletesRound() {
        approved(); proof(); var actual = service.progress(1L, 2L, 3L);
        assertThat(actual.stage()).isEqualTo("ROUND_COMPLETED"); assertThat(actual.checkBatchId()).isEqualTo(9L);
    }
    @Test void changedRevisionOrDependenciesInvalidateOldProof() {
        approved(); var result = proof(); result.setRevisionId(100L);
        assertThat(service.progress(1L, 2L, 3L).stage()).isEqualTo("AWAITING_RECHECK");
        result.setRevisionId(6L); result.setEvidenceHash("old");
        assertThat(service.progress(1L, 2L, 3L).stage()).isEqualTo("AWAITING_RECHECK");
    }
    @Test void failedOrWrongSnapshotBatchCannotCompleteRound() {
        approved(); proof(); var batch = batches.selectById(9L); batch.setSnapshotId(100L);
        assertThat(service.progress(1L, 2L, 3L).stage()).isEqualTo("AWAITING_RECHECK");
        batch.setSnapshotId(2L); batch.setStatus("FAILED");
        assertThat(service.progress(1L, 2L, 3L).stage()).isEqualTo("AWAITING_RECHECK");
    }
    @Test void newDraftTakesPriorityOverOldApprovedKnowledge() {
        var workspace = approved(); proof(); workspace.setWorkingStatus("DRAFT");
        assertThat(service.progress(1L, 2L, 3L).stage()).isEqualTo("HUMAN_EDITING");
        verifyNoInteractions(results, batches);
    }
    @Test void authorizationFailurePreventsReadingEvidence() {
        when(semantics.workspace(1L, 2L, 3L)).thenThrow(new IllegalStateException("无权"));
        assertThatThrownBy(() -> service.progress(1L, 2L, 3L)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(results, batches); verify(semantics, never()).evaluateForGovernance(any(), any());
    }
}
