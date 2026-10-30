package com.dataocean.module.fieldsemantic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.fieldsemantic.entity.po.SemanticCheckResult;
import com.dataocean.module.fieldsemantic.entity.vo.FieldGovernanceProgressVO;
import com.dataocean.module.fieldsemantic.mapper.SemanticCheckBatchMapper;
import com.dataocean.module.fieldsemantic.mapper.SemanticCheckResultMapper;
import com.dataocean.module.fieldsemantic.service.FieldSemanticService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** 复用权威修订和复查证据判断下一步，不新增第二套流程状态。 */
@Service
@RequiredArgsConstructor
public class FieldGovernanceProgressServiceImpl implements com.dataocean.module.fieldsemantic.service.FieldGovernanceProgressService {
    private final FieldSemanticService semanticService;
    private final SemanticCheckResultMapper resultMapper;
    private final SemanticCheckBatchMapper batchMapper;
    private final com.dataocean.module.fieldtag.mapper.FieldTagMapper tags;

    @org.springframework.transaction.annotation.Transactional
    public FieldGovernanceProgressVO progress(Long columnId, Long snapshotId, Long userId) {
        // 先按现有服务校验字段真实来源和当前用户权限，再读取任何流程证据。
        var workspace = semanticService.workspace(columnId, snapshotId, userId);
        var evaluation = semanticService.evaluateForGovernance(columnId, snapshotId);
        String stage;
        String action;
        String reason;
        Long batchId = null;
        if ("NOT_APPLICABLE".equals(evaluation.outcome())) {
            stage = "NOT_APPLICABLE"; action = "VIEW_REASON"; reason = evaluation.reason();
        } else if ("PENDING_REVIEW".equals(workspace.getWorkingStatus())) {
            stage = "AWAITING_REVIEW"; action = "REVIEW"; reason = "当前修订等待人工审核。";
        } else if ("DRAFT".equals(workspace.getWorkingStatus())) {
            stage = "HUMAN_EDITING"; action = "EDIT_AND_SUBMIT"; reason = "完善当前草稿并提交审核。";
        } else if (!evaluation.usable()) {
            stage = "NEEDS_GOVERNANCE"; action = "GENERATE_OR_EDIT"; reason = evaluation.reason();
        } else if(tags.selectCount(new LambdaQueryWrapper<com.dataocean.module.fieldtag.entity.po.FieldTag>()
                .eq(com.dataocean.module.fieldtag.entity.po.FieldTag::getColumnMetaId,columnId)) == 0) {
            stage="NEEDS_CLASSIFICATION";action="CLASSIFY";reason="当前解释已审核，请补充字段业务分类标签后复查。";
        } else {
            stage = "AWAITING_RECHECK"; action = "RECHECK"; reason = "当前解释已审核，需取得覆盖同一修订的复查证据。";
            var result = resultMapper.selectOne(new LambdaQueryWrapper<SemanticCheckResult>()
                    .eq(SemanticCheckResult::getColumnMetaId, columnId)
                    .eq(SemanticCheckResult::getBindingId, evaluation.bindingId())
                    .orderByDesc(SemanticCheckResult::getId).last("LIMIT 1"));
            if (result != null && "VALID".equals(result.getOutcome())
                    && Objects.equals(result.getColumnMetaId(), columnId)
                    && Objects.equals(result.getBindingId(), evaluation.bindingId())
                    && Objects.equals(result.getDefinitionId(), evaluation.definitionId())
                    && Objects.equals(result.getRevisionId(), evaluation.revisionId())
                    && Objects.equals(result.getReviewId(), evaluation.reviewId())
                    && evaluation.evidenceHash() != null && !evaluation.evidenceHash().isBlank()
                    && evaluation.evidenceHash().equals(result.getEvidenceHash())
                    && Objects.equals(result.getRuleVersion(), SemanticCheckServiceImpl.RULE_VERSION)) {
                var batch = batchMapper.selectById(result.getBatchId());
                if (batch != null && "SUCCESS".equals(batch.getStatus())
                        && Objects.equals(batch.getSnapshotId(), snapshotId)
                        && Objects.equals(batch.getDatasourceId(), workspace.getDatasourceId())
                        && Objects.equals(batch.getRuleVersion(), result.getRuleVersion())) {
                    stage = "ROUND_COMPLETED"; action = "VIEW_KNOWLEDGE";
                    reason = "当前修订已有有效复查证据；文档发布与 RAG 构建仍需分别完成。";
                    batchId = batch.getId();
                }
            }
        }
        return new FieldGovernanceProgressVO(columnId, snapshotId, stage, action, reason,
                evaluation.revisionId(), batchId);
    }
}
