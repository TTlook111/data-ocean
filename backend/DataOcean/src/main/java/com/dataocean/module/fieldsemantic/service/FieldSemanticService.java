package com.dataocean.module.fieldsemantic.service;

import com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticActionDTO;
import com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticBindingConfirmDTO;
import com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticDraftDTO;
import com.dataocean.module.fieldsemantic.entity.vo.FieldSemanticWorkspaceVO;
import com.dataocean.module.fieldsemantic.entity.vo.FieldSemanticPendingBindingVO;
import com.dataocean.module.fieldsemantic.service.FieldSemanticEvaluation;

public interface FieldSemanticService {
    FieldSemanticWorkspaceVO workspace(Long columnMetaId, Long snapshotId, Long userId);
    FieldSemanticWorkspaceVO workspaceDetails(Long columnMetaId, Long snapshotId, Long userId);
    FieldSemanticWorkspaceVO saveDraft(Long columnMetaId, FieldSemanticDraftDTO request, Long userId);
    FieldSemanticWorkspaceVO submit(Long columnMetaId, FieldSemanticActionDTO request, Long userId);
    FieldSemanticWorkspaceVO withdraw(Long columnMetaId, FieldSemanticActionDTO request, Long userId);
    FieldSemanticWorkspaceVO review(Long columnMetaId, FieldSemanticActionDTO request, Long userId);
    FieldSemanticWorkspaceVO revoke(Long columnMetaId, FieldSemanticActionDTO request, Long userId);
    FieldSemanticWorkspaceVO confirmBinding(Long columnMetaId, Long snapshotId,
                                            FieldSemanticBindingConfirmDTO request, Long userId);
    void validateBindingConfirmation(Long columnMetaId, Long snapshotId, Long userId);
    java.util.List<FieldSemanticPendingBindingVO> pendingBindingConfirmations(Long snapshotId, Long userId);
    FieldSemanticWorkspaceVO generateCandidate(Long columnMetaId, Long snapshotId, Integer expectedVersion, Long userId);
    FieldSemanticWorkspaceVO adoptCandidate(Long columnMetaId, Long candidateId, FieldSemanticActionDTO request, Long userId);
    FieldSemanticEvaluation evaluate(Long columnMetaId, Long snapshotId);
    boolean membershipValid(Long datasourceId, Long snapshotId, java.util.List<String> factSourceIds);
    java.util.List<java.util.Map<String, Object>> knowledgeFacts(Long datasourceId, Long snapshotId);
    void validateKnowledgeFact(Long datasourceId, Long snapshotId, java.util.Map<String, Object> marker, String body);
    void bindCollectedSnapshot(Long datasourceId, Long snapshotId, Long createdBy);
    java.util.List<java.util.Map<String, Object>> glossaryOptions(Long columnMetaId, Long snapshotId, Long userId);
}
