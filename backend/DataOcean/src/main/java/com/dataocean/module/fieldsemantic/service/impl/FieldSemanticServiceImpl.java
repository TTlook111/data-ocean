package com.dataocean.module.fieldsemantic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.entity.po.Datasource;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticActionDTO;
import com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticBindingConfirmDTO;
import com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticDraftDTO;
import com.dataocean.module.fieldsemantic.entity.po.FieldSemanticBinding;
import com.dataocean.module.fieldsemantic.entity.po.FieldSemanticCandidate;
import com.dataocean.module.fieldsemantic.entity.po.FieldSemanticDefinition;
import com.dataocean.module.fieldsemantic.entity.po.FieldSemanticReview;
import com.dataocean.module.fieldsemantic.entity.po.FieldSemanticRevision;
import com.dataocean.module.fieldsemantic.entity.vo.FieldSemanticWorkspaceVO;
import com.dataocean.module.fieldsemantic.entity.vo.FieldSemanticPendingBindingVO;
import com.dataocean.module.fieldsemantic.mapper.FieldSemanticBindingMapper;
import com.dataocean.module.fieldsemantic.mapper.FieldSemanticCandidateMapper;
import com.dataocean.module.fieldsemantic.mapper.FieldSemanticDefinitionMapper;
import com.dataocean.module.fieldsemantic.mapper.FieldSemanticReviewMapper;
import com.dataocean.module.fieldsemantic.mapper.FieldSemanticRevisionMapper;
import com.dataocean.module.fieldsemantic.service.FieldSemanticService;
import com.dataocean.module.fieldsemantic.service.FieldSemanticEvaluation;
import com.dataocean.module.glossary.entity.po.GlossaryTerm;
import com.dataocean.module.glossary.entity.po.GlossaryTermReview;
import com.dataocean.module.glossary.entity.vo.TermColumnVO;
import com.dataocean.module.glossary.mapper.GlossaryTermMapper;
import com.dataocean.module.glossary.mapper.GlossaryTermReviewMapper;
import com.dataocean.module.glossary.service.GlossaryTermWorkflowService;
import com.dataocean.module.knowledge.client.PythonKnowledgeClient;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.entity.po.TableRelation;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** 字段解释权威记录。AI 仅写候选，审核通过后才切换精确来源上的有效修订。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FieldSemanticServiceImpl implements FieldSemanticService {
    private static final String FIELD_VIEW = "governance:field:view";
    private static final String FIELD_MANAGE = "governance:field:manage";
    private static final String KNOWLEDGE_VIEW = "knowledge:view";
    private static final String KNOWLEDGE_MANAGE = "knowledge:manage";
    private static final String KNOWLEDGE_APPROVE = "knowledge:approve";
    private static final String VALID = "VALID";
    private static final String ACTIVE = "ACTIVE";

    private final FieldSemanticDefinitionMapper definitionMapper;
    private final FieldSemanticBindingMapper bindingMapper;
    private final FieldSemanticRevisionMapper revisionMapper;
    private final FieldSemanticReviewMapper reviewMapper;
    private final FieldSemanticCandidateMapper candidateMapper;
    private final DbColumnMetaMapper columnMapper;
    private final DbTableMetaMapper tableMapper;
    private final MetadataSnapshotMapper snapshotMapper;
    private final TableRelationMapper tableRelationMapper;
    private final DatasourceMapper datasourceMapper;
    private final IamS1AdminGuard adminGuard;
    private final IamS1CapabilityService capabilityService;
    private final GlossaryTermMapper glossaryTermMapper;
    private final GlossaryTermReviewMapper glossaryReviewMapper;
    private final GlossaryTermWorkflowService glossaryWorkflow;
    private final PythonKnowledgeClient pythonClient;
    private final ObjectMapper objectMapper;
    private final GovernanceExecutionFence executionFence;
    private final com.dataocean.module.fieldtag.mapper.FieldTagMapper fieldTags;

    @Override
    @Transactional
    public FieldSemanticWorkspaceVO workspace(Long columnMetaId, Long snapshotId, Long userId) {
        Source source = source(columnMetaId, snapshotId);
        require(userId, FIELD_VIEW, source.datasourceId());
        FieldSemanticDefinition definition = findDefinition(source);
        FieldSemanticBinding binding = definition == null ? null : findBinding(definition.getId(), snapshotId);
        if (binding != null) binding = currentBinding(definition, source, binding);
        return toWorkspace(source, definition, binding, false);
    }

    @Override
    @Transactional
    public FieldSemanticWorkspaceVO workspaceDetails(Long columnMetaId, Long snapshotId, Long userId) {
        Source source = source(columnMetaId, snapshotId);
        require(userId, FIELD_VIEW, source.datasourceId());
        require(userId, KNOWLEDGE_VIEW, source.datasourceId());
        FieldSemanticDefinition definition = findDefinition(source);
        FieldSemanticBinding binding = definition == null ? null : findBinding(definition.getId(), snapshotId);
        if (binding != null) binding = currentBinding(definition, source, binding);
        return toWorkspace(source, definition, binding, true);
    }

    @Override
    @Transactional
    public FieldSemanticWorkspaceVO saveDraft(Long columnMetaId, FieldSemanticDraftDTO request, Long userId) {
        Source source = source(columnMetaId, request.getSnapshotId());
        requireWritable(userId, source.datasourceId());
        requireUsableSource(source);
        FieldSemanticDefinition definition = findDefinition(source);
        boolean creatingDefinition = definition == null;
        if (creatingDefinition) {
            if (!Integer.valueOf(0).equals(request.getExpectedVersion())) {
                throw new BusinessException(409, "字段解释版本已变化，请刷新后重试");
            }
            definition = getOrCreateDefinition(source, userId);
        }
        definition = lockDefinition(definition);
        if (!creatingDefinition) checkVersion(definition, request.getExpectedVersion());
        FieldSemanticBinding binding = getOrCreateBinding(definition, source, userId);
        binding = currentBinding(definition, source, binding);
        List<Map<String, Object>> glossaryRefs = normalizeGlossaryRefs(request.getGlossaryReviewIds(), source, userId);
        requireText(request.getBusinessName(), 200, "业务名称");
        requireText(request.getMeaning(), 4000, "字段含义");
        requireText(request.getEvidence(), 4000, "业务依据");
        FieldSemanticRevision revision = newRevision(definition, binding, request.getBusinessName(), request.getMeaning(),
                request.getUnit(), request.getCurrency(), request.getEnumSemantics(), request.getMetricDefinition(),
                request.getApplicability(), request.getEvidence(), request.getUnresolvedItems(), glossaryRefs, userId);
        FieldSemanticRevision oldWork = currentWorking(definition);
        if (oldWork != null && !Objects.equals(oldWork.getBindingId(), binding.getId())
                && Set.of("DRAFT", "PENDING_REVIEW").contains(oldWork.getStatus())) {
            throw new BusinessException(409, "另一快照上仍有未完成修订；请先切回该快照完成或撤回，再编辑当前来源");
        }
        if (oldWork != null && "PENDING_REVIEW".equals(oldWork.getStatus())) {
            throw new BusinessException(409, "待审核修订已固定，请先撤回后再编辑");
        }
        if (oldWork != null && "DRAFT".equals(oldWork.getStatus())) {
            oldWork.setStatus("WITHDRAWN");
            revisionMapper.updateById(oldWork);
            FieldSemanticBinding previousBinding = bindingMapper.selectById(oldWork.getBindingId());
            if (previousBinding != null) insertReview(definition, oldWork, previousBinding,
                    "DRAFT_REPLACED", "人工继续编辑并创建新修订", userId, definition.getRowVersion());
        }
        revision.setBasedOnRevisionId(definition.getCurrentApprovedRevisionId());
        revisionMapper.insert(revision);
        insertReview(definition, revision, binding, "DRAFT_SAVED", "保存字段解释草稿", userId,
                definition.getRowVersion());
        updateDefinition(definition, definition.getCurrentBindingId(), definition.getCurrentApprovedRevisionId(),
                revision.getId(), ACTIVE, userId, "字段语义草稿已被其他操作修改，请刷新后重试");
        return workspaceAfterMutation(columnMetaId, request.getSnapshotId());
    }

    @Override
    @Transactional
    public FieldSemanticWorkspaceVO submit(Long columnMetaId, FieldSemanticActionDTO request, Long userId) {
        FieldSemanticDefinition definition = requireDefinitionForColumn(columnMetaId);
        definition = lockDefinition(definition);
        requireWritable(userId, definition.getDatasourceId());
        checkVersion(definition, request.getExpectedVersion());
        FieldSemanticRevision draft = currentWorking(definition);
        if (draft == null || !"DRAFT".equals(draft.getStatus())) throw new BusinessException(409, "没有可提交的字段解释草稿");
        FieldSemanticBinding binding = bindingMapper.selectById(draft.getBindingId());
        Source source = source(binding == null ? null : binding.getColumnMetaId(), binding == null ? null : binding.getSnapshotId());
        validateBindingSource(binding, source);
        validateRevisionHashes(draft, binding, source);
        draft.setStatus("PENDING_REVIEW");
        draft.setSubmittedAt(LocalDateTime.now());
        revisionMapper.updateById(draft);
        FieldSemanticBinding submittedBinding = bindingMapper.selectById(draft.getBindingId());
        insertReview(definition, draft, submittedBinding, "SUBMIT",
                StringUtils.hasText(request.getReason()) ? request.getReason() : "提交字段解释审核", userId,
                definition.getRowVersion());
        updateDefinition(definition, definition.getCurrentBindingId(), definition.getCurrentApprovedRevisionId(),
                draft.getId(), ACTIVE, userId, "字段解释版本已变化，请刷新后重试");
        return workspaceAfterMutation(columnMetaId, binding.getSnapshotId());
    }

    @Override
    @Transactional
    public FieldSemanticWorkspaceVO withdraw(Long columnMetaId, FieldSemanticActionDTO request, Long userId) {
        FieldSemanticDefinition definition = requireDefinitionForColumn(columnMetaId);
        definition = lockDefinition(definition);
        requireWritable(userId, definition.getDatasourceId());
        checkVersion(definition, request.getExpectedVersion());
        FieldSemanticRevision pending = currentWorking(definition);
        if (pending == null || !"PENDING_REVIEW".equals(pending.getStatus())) throw new BusinessException(409, "当前没有待撤回的字段解释");
        FieldSemanticBinding binding = bindingMapper.selectById(pending.getBindingId());
        pending.setStatus("WITHDRAWN");
        revisionMapper.updateById(pending);
        if (!StringUtils.hasText(request.getReason())) throw new BusinessException(400, "撤回待审核修订时必须填写原因");
        insertReview(definition, pending, binding, "WITHDRAW", request.getReason(), userId,
                definition.getRowVersion());
        updateDefinition(definition, definition.getCurrentBindingId(), definition.getCurrentApprovedRevisionId(),
                null, ACTIVE, userId, "待审核修订已被其他操作修改，请刷新后重试");
        return workspaceAfterMutation(columnMetaId, binding.getSnapshotId());
    }

    @Override
    @Transactional
    public FieldSemanticWorkspaceVO review(Long columnMetaId, FieldSemanticActionDTO request, Long userId) {
        FieldSemanticDefinition definition = requireDefinitionForColumn(columnMetaId);
        definition = lockDefinition(definition);
        require(userId, FIELD_VIEW, definition.getDatasourceId());
        require(userId, KNOWLEDGE_APPROVE, definition.getDatasourceId());
        checkVersion(definition, request.getExpectedVersion());
        FieldSemanticRevision pending = currentWorking(definition);
        if (pending == null || !"PENDING_REVIEW".equals(pending.getStatus())
                || request.getRevisionId() == null || !request.getRevisionId().equals(pending.getId())) {
            throw new BusinessException(409, "待审核修订已变化，请刷新后重试");
        }
        if (!"APPROVE".equals(request.getAction()) && !"REJECT".equals(request.getAction())) {
            throw new BusinessException(400, "审核动作只能是通过或驳回");
        }
        FieldSemanticBinding binding = bindingMapper.selectById(pending.getBindingId());
        Source source = source(binding == null ? null : binding.getColumnMetaId(), binding == null ? null : binding.getSnapshotId());
        validateBindingSource(binding, source);
        validateRevisionHashes(pending, binding, source);
        if ("REJECT".equals(request.getAction()) && !StringUtils.hasText(request.getReason())) {
            throw new BusinessException(400, "驳回字段解释时必须填写原因");
        }
        if ("APPROVE".equals(request.getAction()) && !StringUtils.hasText(request.getReason())) {
            throw new BusinessException(400, "审核通过时请记录审核依据");
        }
        if ("APPROVE".equals(request.getAction())) {
            if(!request.isEvidenceConfirmed()||!StringUtils.hasText(request.getEvidenceReference()))throw new BusinessException("审核通过前请明确确认已核对原始资料，并填写资料名称、版本和位置；AI 自述不能作为依据");
            requireText(request.getEvidenceReference(),1000,"原始资料引用");requireText(request.getReason(),800,"人工审核依据");
            String confirmedEvidence="原始资料："+request.getEvidenceReference().trim()+"\n人工核对："+request.getReason().trim();
            requireUsableSource(source);
            pending.setStatus("APPROVED");
            binding.setStatus(VALID);
            binding.setStatusReason(null);
            binding.setConfirmationEvidence(trim(confirmedEvidence, 1000));
            binding.setConfirmedBy(userId);
            binding.setConfirmedAt(LocalDateTime.now());
            bindingMapper.updateById(binding);
            insertReview(definition, pending, binding, "APPROVE", confirmedEvidence, userId,
                    definition.getRowVersion());
            revisionMapper.updateById(pending);
            updateDefinition(definition, binding.getId(), pending.getId(), null, ACTIVE, userId,
                    "字段解释审核期间记录已变化，请刷新后重试");
        } else {
            pending.setStatus("REJECTED");
            revisionMapper.updateById(pending);
            insertReview(definition, pending, binding, "REJECT", request.getReason(), userId,
                    definition.getRowVersion());
            updateDefinition(definition, definition.getCurrentBindingId(), definition.getCurrentApprovedRevisionId(),
                    null, ACTIVE, userId, "字段解释审核期间记录已变化，请刷新后重试");
        }
        return workspaceAfterMutation(columnMetaId, binding.getSnapshotId());
    }

    @Override
    @Transactional
    public FieldSemanticWorkspaceVO revoke(Long columnMetaId, FieldSemanticActionDTO request, Long userId) {
        FieldSemanticDefinition definition = requireDefinitionForColumn(columnMetaId);
        definition = lockDefinition(definition);
        require(userId, FIELD_MANAGE, definition.getDatasourceId());
        require(userId, KNOWLEDGE_APPROVE, definition.getDatasourceId());
        checkVersion(definition, request.getExpectedVersion());
        FieldSemanticRevision current = definition.getCurrentApprovedRevisionId() == null
                ? null : revisionMapper.selectById(definition.getCurrentApprovedRevisionId());
        FieldSemanticBinding binding = definition.getCurrentBindingId() == null
                ? null : bindingMapper.selectById(definition.getCurrentBindingId());
        if (current == null || binding == null || !"APPROVED".equals(current.getStatus()) || !VALID.equals(binding.getStatus())) {
            throw new BusinessException(409, "没有当前有效的字段解释可撤销");
        }
        if (!StringUtils.hasText(request.getReason())) throw new BusinessException(400, "撤销字段解释时必须填写原因");
        insertReview(definition, current, binding, "REVOKE", request.getReason(), userId,
                definition.getRowVersion());
        updateDefinition(definition, binding.getId(), null, definition.getWorkingRevisionId(), "REVOKED", userId,
                "字段解释已被其他操作更新，请刷新后重试");
        return workspaceAfterMutation(columnMetaId, binding.getSnapshotId());
    }

    @Override
    @Transactional(readOnly = true)
    public void validateBindingConfirmation(Long columnMetaId, Long snapshotId, Long userId) {
        Source source = source(columnMetaId, snapshotId);
        requireWritable(userId, source.datasourceId());
        requireUsableSource(source);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FieldSemanticPendingBindingVO> pendingBindingConfirmations(Long snapshotId, Long userId) {
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) throw new BusinessException(404, "来源快照不存在");
        requireWritable(userId, snapshot.getDatasourceId());
        List<FieldSemanticBinding> pending = bindingMapper.selectList(new LambdaQueryWrapper<FieldSemanticBinding>()
                .eq(FieldSemanticBinding::getSnapshotId, snapshotId)
                .eq(FieldSemanticBinding::getStatus, "PENDING_CONFIRMATION")
                .orderByAsc(FieldSemanticBinding::getId));
        List<FieldSemanticPendingBindingVO> result = new ArrayList<>();
        for (FieldSemanticBinding binding : pending) {
            FieldSemanticDefinition definition = definitionMapper.selectById(binding.getDefinitionId());
            if (definition == null) continue;
            Source source = source(binding.getColumnMetaId(), snapshotId);
            FieldSemanticBinding active = definition.getCurrentBindingId() == null
                    ? null : bindingMapper.selectById(definition.getCurrentBindingId());
            FieldSemanticRevision current = definition.getCurrentApprovedRevisionId() == null
                    ? null : revisionMapper.selectById(definition.getCurrentApprovedRevisionId());
            String reason = null;
            if (active == null || current == null || !"APPROVED".equals(current.getStatus())) {
                reason = "原来源没有当前有效的审核解释";
            } else if (!parseMapList(current.getGlossaryRefsJson()).isEmpty()) {
                reason = "解释引用了绑定旧快照的术语，需创建新修订并重新审核";
            } else if (forbidden(source.column().getGovernanceStatus()) || forbidden(source.table().getGovernanceStatus())) {
                reason = "字段或表已 BLOCKED/DEPRECATED，不能确认来源";
            } else if (!Objects.equals(binding.getSchemaFingerprint(), fingerprint(source))
                    || !Objects.equals(active.getSchemaFingerprint(), fingerprint(source))) {
                reason = "字段结构或完整依据已变化，需创建新修订并重新审核";
            }
            FieldSemanticPendingBindingVO item = new FieldSemanticPendingBindingVO();
            item.setColumnMetaId(binding.getColumnMetaId());
            item.setExpectedVersion(definition.getRowVersion());
            item.setTableName(source.table().getTableName());
            item.setColumnName(source.column().getColumnName());
            item.setBusinessName(current == null ? null : current.getBusinessName());
            item.setEligible(reason == null);
            item.setReason(reason);
            result.add(item);
        }
        return result;
    }

    @Override
    @Transactional(noRollbackFor = BindingRequiresReviewException.class)
    public FieldSemanticWorkspaceVO confirmBinding(Long columnMetaId, Long snapshotId,
                                                    FieldSemanticBindingConfirmDTO request, Long userId) {
        Source source = source(columnMetaId, snapshotId);
        requireWritable(userId, source.datasourceId());
        requireUsableSource(source);
        FieldSemanticDefinition definition = requireDefinition(source);
        definition = lockDefinition(definition);
        checkVersion(definition, request.getExpectedVersion());
        FieldSemanticBinding target = findBinding(definition.getId(), snapshotId);
        FieldSemanticBinding active = definition.getCurrentBindingId() == null
                ? null : bindingMapper.selectById(definition.getCurrentBindingId());
        FieldSemanticRevision current = definition.getCurrentApprovedRevisionId() == null
                ? null : revisionMapper.selectById(definition.getCurrentApprovedRevisionId());
        if (target == null || active == null || current == null || !"PENDING_CONFIRMATION".equals(target.getStatus())) {
            throw new BusinessException(409, "没有待确认的来源绑定");
        }
        if (!parseMapList(current.getGlossaryRefsJson()).isEmpty()) {
            throw new BusinessException(409, "该解释引用了绑定旧快照的术语，需创建针对新来源的新修订后再审核");
        }
        String fingerprint = fingerprint(source);
        if (!Objects.equals(target.getSchemaFingerprint(), fingerprint)
                || !Objects.equals(active.getSchemaFingerprint(), fingerprint)) {
            target.setStatus("NEEDS_REVIEW");
            target.setStatusReason("字段结构或依据与原审核版本不同，需要创建新修订审核");
            bindingMapper.updateById(target);
            throw new BindingRequiresReviewException("来源字段已发生变化，不能沿用旧审核；请编辑并重新提交解释");
        }
        if (!StringUtils.hasText(request.getEvidence())) throw new BusinessException(400, "绑定确认必须填写核验依据");
        target.setStatus(VALID);
        target.setStatusReason(null);
        target.setConfirmationEvidence(trim(request.getEvidence(), 1000));
        target.setConfirmedBy(userId);
        target.setConfirmedAt(LocalDateTime.now());
        bindingMapper.updateById(target);
        insertReview(definition, current, target, "BIND_CONFIRM", request.getEvidence(), userId,
                definition.getRowVersion());
        updateDefinition(definition, target.getId(), current.getId(), definition.getWorkingRevisionId(), ACTIVE, userId,
                "字段来源绑定已被其他操作修改，请刷新后重试");
        return workspaceAfterMutation(columnMetaId, snapshotId);
    }

    @Override
    public FieldSemanticWorkspaceVO generateCandidate(Long columnMetaId, Long snapshotId, Integer expectedVersion, Long userId) {
        return generateCandidate(columnMetaId,snapshotId,expectedVersion,userId,null,null);
    }
    @Override
    public FieldSemanticWorkspaceVO generateCandidate(Long columnMetaId, Long snapshotId, Integer expectedVersion, Long userId,Long governanceTaskId,String executionToken) {
        Source source = source(columnMetaId, snapshotId);
        requireWritable(userId, source.datasourceId());
        requireUsableSource(source);
        FieldSemanticDefinition definition = findDefinition(source);
        if (definition == null) {
            if (!Integer.valueOf(0).equals(expectedVersion)) throw new BusinessException(409, "字段解释版本已变化，请刷新后重试");
            definition = getOrCreateDefinition(source, userId);
        } else checkVersion(definition, expectedVersion);
        FieldSemanticBinding binding = getOrCreateBinding(definition, source, userId);
        binding = currentBinding(definition, source, binding);
        Map<String, Object> input = candidateInput(source, userId);
        String inputHash = sha256(canonical(input));
        FieldSemanticCandidate candidate = new FieldSemanticCandidate();
        candidate.setDefinitionId(definition.getId());
        candidate.setBindingId(binding.getId());
        candidate.setSnapshotId(snapshotId);
        candidate.setBaseRevisionId(definition.getCurrentApprovedRevisionId());
        candidate.setBaseRowVersion(definition.getRowVersion());
        candidate.setStatus("RUNNING");
        candidate.setInputHash(inputHash);
        candidate.setInputJson(json(input));
        candidate.setCreatedBy(userId);
        candidate.setCreatedAt(LocalDateTime.now());
        candidateMapper.insert(candidate);
        Map<String, Object> suggestion;
        try {
            suggestion = pythonClient.generateFieldSemanticCandidate(input);
        } catch (RuntimeException exception) {
            candidate.setStatus("FAILED");
            candidate.setFailureReason("AI 候选生成失败；可继续人工填写。");
            candidate.setCompletedAt(LocalDateTime.now());
            candidateMapper.updateById(candidate);
            throw exception;
        }
        var baseDefinition=definition;var baseBinding=binding;
        try{return executionFence.persist(governanceTaskId,executionToken,userId,()->completeCandidate(columnMetaId,snapshotId,userId,source,baseDefinition,baseBinding,candidate,suggestion));}
        catch(BusinessException exception){if(!"STALE".equals(candidate.getStatus())){candidate.setStatus("STALE");candidate.setFailureReason("执行已过期或来源发生变化，本候选不能采纳");candidate.setCompletedAt(LocalDateTime.now());candidateMapper.updateById(candidate);}throw exception;}
    }
    private FieldSemanticWorkspaceVO completeCandidate(Long columnMetaId,Long snapshotId,Long userId,Source source,FieldSemanticDefinition definition,FieldSemanticBinding binding,FieldSemanticCandidate candidate,Map<String,Object> suggestion){
        // AI response is bound to the exact permission, field, source and working version captured above.
        requireWritable(userId, source.datasourceId());
        Source latestSource = source(columnMetaId, snapshotId);
        FieldSemanticDefinition latest = definitionMapper.selectById(definition.getId());
        FieldSemanticBinding latestBinding = bindingMapper.selectById(binding.getId());
        if (latest == null || latestBinding == null || !Objects.equals(latest.getRowVersion(), candidate.getBaseRowVersion())
                || !Objects.equals(fingerprint(source), fingerprint(latestSource))
                || !Objects.equals(binding.getSchemaFingerprint(), fingerprint(latestSource))) {
            candidate.setStatus("STALE");
            candidate.setFailureReason("字段、来源或修订版本在 AI 生成期间发生变化；本候选不能采纳。");
            candidate.setCompletedAt(LocalDateTime.now());
            candidateMapper.updateById(candidate);
            throw new BusinessException(409, "AI 响应已过期，请刷新后重新生成");
        }
        try {
            candidateGlossaryRefs(candidate, candidate.getCreatedBy());
        } catch (BusinessException exception) {
            candidate.setStatus("STALE");
            candidate.setFailureReason("AI 使用的术语依据已变化；本候选不能采纳。");
            candidate.setCompletedAt(LocalDateTime.now());
            candidateMapper.updateById(candidate);
            throw new BusinessException(409, "AI 使用的已审核术语已变化，请重新生成");
        }
        candidate.setBusinessName(stringValue(suggestion.get("businessName")));
        candidate.setMeaning(stringValue(suggestion.get("meaning")));
        candidate.setUnit(stringValue(suggestion.get("unit")));
        candidate.setCurrency(stringValue(suggestion.get("currency")));
        candidate.setEnumSemantics(stringValue(suggestion.get("enumSemantics")));
        candidate.setMetricDefinition(stringValue(suggestion.get("metricDefinition")));
        candidate.setApplicability(stringValue(suggestion.get("applicability")));
        candidate.setEvidence(stringValue(suggestion.get("evidence")));
        candidate.setUnresolvedItemsJson(json(stringList(suggestion.get("unresolvedItems"))));
        candidate.setStatus("READY");
        candidate.setCompletedAt(LocalDateTime.now());
        candidateMapper.updateById(candidate);
        var workspace=workspaceAfterMutation(columnMetaId, snapshotId);
        workspace.setGeneratedCandidateId(candidate.getId());return workspace;
    }

    @Override
    @Transactional
    public FieldSemanticWorkspaceVO adoptCandidate(Long columnMetaId, Long candidateId,
                                                   FieldSemanticActionDTO request, Long userId) {
        FieldSemanticCandidate candidate = candidateMapper.selectById(candidateId);
        if (candidate == null || !columnMetaId.equals(bindingColumn(candidate.getBindingId()))) {
            throw new BusinessException(404, "字段释义候选不存在");
        }
        Source source = source(columnMetaId, candidate.getSnapshotId());
        requireWritable(userId, source.datasourceId());
        FieldSemanticDefinition definition = definitionMapper.selectById(candidate.getDefinitionId());
        definition = lockDefinition(definition);
        checkVersion(definition, request.getExpectedVersion());
        if (!"READY".equals(candidate.getStatus()) || !Objects.equals(candidate.getBaseRowVersion(), definition.getRowVersion())
                || !Objects.equals(candidate.getBaseRevisionId(), definition.getCurrentApprovedRevisionId())) {
            throw new BusinessException(409, "AI 候选基于过期字段或修订版本，不能采纳");
        }
        FieldSemanticBinding binding = bindingMapper.selectById(candidate.getBindingId());
        if (binding == null || !Objects.equals(binding.getSchemaFingerprint(), fingerprint(source))) {
            throw new BusinessException(409, "AI 候选来源已变化，不能采纳");
        }
        FieldSemanticRevision draft = newRevision(definition, binding, candidate.getBusinessName(), candidate.getMeaning(),
                candidate.getUnit(), candidate.getCurrency(), candidate.getEnumSemantics(), candidate.getMetricDefinition(),
                candidate.getApplicability(), candidate.getEvidence(), readStringList(candidate.getUnresolvedItemsJson()),
                candidateGlossaryRefs(candidate, userId), userId);
        draft.setBasedOnRevisionId(definition.getCurrentApprovedRevisionId());
        FieldSemanticRevision oldWork = currentWorking(definition);
        if (oldWork != null && "PENDING_REVIEW".equals(oldWork.getStatus())) throw new BusinessException(409, "待审核修订已固定，请先撤回");
        if (oldWork != null && "DRAFT".equals(oldWork.getStatus())) {
            oldWork.setStatus("WITHDRAWN"); revisionMapper.updateById(oldWork);
            FieldSemanticBinding previousBinding = bindingMapper.selectById(oldWork.getBindingId());
            if (previousBinding != null) insertReview(definition, oldWork, previousBinding,
                    "DRAFT_REPLACED", "采纳新的 AI 候选并创建新修订", userId, definition.getRowVersion());
        }
        revisionMapper.insert(draft);
        insertReview(definition, draft, binding, "AI_ADOPTED", "采纳 AI 候选，仍需人工审核", userId,
                definition.getRowVersion());
        candidate.setStatus("ADOPTED");
        candidate.setAdoptedRevisionId(draft.getId());
        candidate.setAdoptedBy(userId);
        candidate.setAdoptedAt(LocalDateTime.now());
        candidateMapper.updateById(candidate);
        updateDefinition(definition, definition.getCurrentBindingId(), definition.getCurrentApprovedRevisionId(),
                draft.getId(), ACTIVE, userId, "AI 候选采纳期间字段状态已变化，请刷新");
        return workspaceAfterMutation(columnMetaId, candidate.getSnapshotId());
    }

    @Override
    @Transactional(readOnly = true)
    public FieldSemanticEvaluation evaluate(Long columnMetaId, Long snapshotId) {
        return evaluateInternal(columnMetaId,snapshotId,false);
    }
    @Override
    @Transactional(readOnly = true)
    public FieldSemanticEvaluation evaluateForGovernance(Long columnMetaId,Long snapshotId){
        return evaluateInternal(columnMetaId,snapshotId,true);
    }
    private FieldSemanticEvaluation evaluateInternal(Long columnMetaId, Long snapshotId, boolean governance) {
        Source source;
        try { source = source(columnMetaId, snapshotId); }
        catch (BusinessException exception) {
            return evaluation("NOT_APPLICABLE", "NOT_APPLICABLE", "字段来源已不存在或不能与当前快照核验。",
                    null, null, null, null, null, null, null);
        }
        if (forbidden(source.column().getGovernanceStatus()) || forbidden(source.table().getGovernanceStatus())
                || !governance && (!List.of("NORMAL", "RECOMMENDED", "SENSITIVE").contains(source.column().getGovernanceStatus())
                || !List.of("NORMAL", "RECOMMENDED", "SENSITIVE").contains(source.table().getGovernanceStatus()))) {
            return evaluation("NOT_APPLICABLE", "NOT_APPLICABLE", "字段或所属表不在当前合规使用范围。",
                    null, null, null, null, null, null, sha256(canonical(List.of(source.snapshotId(), source.column().getId(), fingerprint(source)))));
        }
        FieldSemanticDefinition definition = findDefinition(source);
        if (definition == null) {
            return evaluation("ISSUE", "MISSING_FIELD_SEMANTIC", "字段尚无经过审核且适用当前来源的业务解释。",
                    null, null, null, null, null, null, sha256(canonical(List.of(source.snapshotId(), source.column().getId(), fingerprint(source)))));
        }
        FieldSemanticBinding binding = findBinding(definition.getId(), snapshotId);
        FieldSemanticRevision revision = definition.getCurrentApprovedRevisionId() == null
                ? null : revisionMapper.selectById(definition.getCurrentApprovedRevisionId());
        if (revision == null) {
            boolean everApproved = revisionMapper.selectCount(new LambdaQueryWrapper<FieldSemanticRevision>()
                    .eq(FieldSemanticRevision::getDefinitionId, definition.getId())
                    .eq(FieldSemanticRevision::getStatus, "APPROVED")) > 0
                    || "REVOKED".equals(definition.getStatus());
            String reason = everApproved ? "已审核解释已撤销或当前指针失效，不会自动回退到历史修订。"
                    : "字段尚无经过审核且适用当前来源的业务解释。";
            return evaluation("ISSUE", everApproved ? "STALE_FIELD_SEMANTIC" : "MISSING_FIELD_SEMANTIC", reason,
                    definition.getId(), binding == null ? null : binding.getId(), null, null,
                    null, null, evidenceHash(source, definition, binding, null, null));
        }
        if (!"APPROVED".equals(revision.getStatus()) || binding == null
                || !Objects.equals(definition.getCurrentBindingId(), binding.getId()) || !VALID.equals(binding.getStatus())) {
            return evaluation("ISSUE", "STALE_FIELD_SEMANTIC", binding == null
                            ? "已审核解释没有当前快照的有效来源绑定。"
                            : binding.getStatusReason() == null ? "已审核解释的来源绑定或当前审核状态已失效。" : binding.getStatusReason(),
                    definition.getId(), binding == null ? null : binding.getId(), revision.getId(), null,
                    revision.getContentHash(), revision.getDependencyHash(), evidenceHash(source, definition, binding, revision, null));
        }
        FieldSemanticReview review = reviewMapper.selectOne(new LambdaQueryWrapper<FieldSemanticReview>()
                .eq(FieldSemanticReview::getDefinitionId, definition.getId())
                .eq(FieldSemanticReview::getRevisionId, revision.getId())
                .eq(FieldSemanticReview::getBindingId, binding.getId())
                .in(FieldSemanticReview::getAction, "APPROVE", "BIND_CONFIRM")
                .eq(FieldSemanticReview::getContentHash, revision.getContentHash())
                .orderByDesc(FieldSemanticReview::getId).last("LIMIT 1"));
        if (review == null) {
            return evaluation("ISSUE", "STALE_FIELD_SEMANTIC", "缺少与当前修订、来源绑定和依赖 hash 匹配的审核证据。",
                    definition.getId(), binding.getId(), revision.getId(), null,
                    revision.getContentHash(), revision.getDependencyHash(), evidenceHash(source, definition, binding, revision, null));
        }
        var missing=FieldSemanticCompletenessPolicy.missing(revision,new java.util.HashSet<>(tagCodes(columnMetaId)));
        if(!missing.isEmpty())return evaluation("ISSUE","INCOMPLETE_FIELD_SEMANTIC","当前字段业务角色还缺少："+String.join("、",missing),
                definition.getId(),binding.getId(),revision.getId(),review.getId(),revision.getContentHash(),revision.getDependencyHash(),evidenceHash(source,definition,binding,revision,review));
        try {
            validateBindingSource(binding, source);
            List<Map<String, Object>> refs = parseMapList(revision.getGlossaryRefsJson());
            List<Long> reviewIds = refs.stream().map(ref -> ((Number) ref.get("reviewId")).longValue()).toList();
            if (!normalizeGlossaryRefs(reviewIds, source).equals(refs)) throw new BusinessException(409, "术语审核依赖已变化");
            if ("APPROVE".equals(review.getAction())) {
                if (!Objects.equals(revision.getBindingId(), binding.getId())) throw new BusinessException(409, "审核凭据与原始来源绑定不一致");
                validateRevisionHashes(revision, binding, source);
                if (!Objects.equals(review.getDependencyHash(), revision.getDependencyHash())) throw new BusinessException(409, "审核依赖 hash 不一致");
            } else if ("BIND_CONFIRM".equals(review.getAction())) {
                if (!refs.isEmpty() || !Objects.equals(review.getDependencyHash(), dependencyHash(binding, List.of()))) {
                    throw new BusinessException(409, "绑定确认缺少与新来源一致的完整依赖审核");
                }
                FieldSemanticBinding originalBinding = bindingMapper.selectById(revision.getBindingId());
                if (originalBinding == null || !Objects.equals(originalBinding.getDatasourceId(), binding.getDatasourceId())) {
                    throw new BusinessException(409, "原审核来源绑定不存在或跨源");
                }
                Source originalSource = source(originalBinding.getColumnMetaId(), originalBinding.getSnapshotId());
                validateRevisionHashes(revision, originalBinding, originalSource);
            }
        } catch (BusinessException exception) {
            return evaluation("ISSUE", "STALE_FIELD_SEMANTIC", "字段结构、依赖或术语审核依据已失效，需要复核。",
                    definition.getId(), binding.getId(), revision.getId(), review.getId(),
                    revision.getContentHash(), review.getDependencyHash(), evidenceHash(source, definition, binding, revision, review));
        }
        return evaluation("VALID", null, "当前解释、审核、来源绑定及依赖均有效。", definition.getId(), binding.getId(),
                revision.getId(), review.getId(), revision.getContentHash(), review.getDependencyHash(),
                evidenceHash(source, definition, binding, revision, review));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean membershipValid(Long datasourceId, Long snapshotId, List<String> factSourceIds) {
        try {
            if (datasourceId == null || snapshotId == null || factSourceIds == null || factSourceIds.size() != 4) return false;
            Long definitionId = Long.valueOf(factSourceIds.get(0));
            Long revisionId = Long.valueOf(factSourceIds.get(1));
            Long reviewId = Long.valueOf(factSourceIds.get(2));
            Long bindingId = Long.valueOf(factSourceIds.get(3));
            FieldSemanticDefinition definition = definitionMapper.selectById(definitionId);
            FieldSemanticBinding binding = bindingMapper.selectById(bindingId);
            FieldSemanticRevision revision = revisionMapper.selectById(revisionId);
            FieldSemanticReview review = reviewMapper.selectById(reviewId);
            if (definition == null || binding == null || revision == null || review == null
                    || !datasourceId.equals(binding.getDatasourceId()) || !snapshotId.equals(binding.getSnapshotId())
                    || !definitionId.equals(binding.getDefinitionId()) || !definitionId.equals(revision.getDefinitionId())
                    || (!"BIND_CONFIRM".equals(review.getAction()) && !bindingId.equals(revision.getBindingId()))
                    || !definitionId.equals(review.getDefinitionId())
                    || !revisionId.equals(review.getRevisionId()) || !bindingId.equals(review.getBindingId())
                    || !Objects.equals(definition.getCurrentBindingId(), bindingId)
                    || !Objects.equals(definition.getCurrentApprovedRevisionId(), revisionId)
                    || !Objects.equals(revision.getContentHash(), review.getContentHash())
                    || !Set.of("APPROVE", "BIND_CONFIRM").contains(review.getAction())) return false;
            FieldSemanticEvaluation evaluation = evaluate(binding.getColumnMetaId(), snapshotId);
            return evaluation.usable() && Objects.equals(evaluation.definitionId(), definitionId)
                    && Objects.equals(evaluation.bindingId(), bindingId)
                    && Objects.equals(evaluation.revisionId(), revisionId)
                    && Objects.equals(evaluation.reviewId(), reviewId);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    @Transactional
    public void bindCollectedSnapshot(Long datasourceId, Long snapshotId, Long createdBy) {
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        Datasource datasource = datasourceMapper.selectById(datasourceId);
        if (snapshot == null || datasource == null || !datasourceId.equals(snapshot.getDatasourceId())) {
            throw new BusinessException(409, "新采集快照的数据源归属无法核验");
        }
        List<FieldSemanticDefinition> definitions = definitionMapper.selectList(new LambdaQueryWrapper<FieldSemanticDefinition>()
                .eq(FieldSemanticDefinition::getDatasourceId, datasourceId));
        if (definitions.isEmpty()) return;
        List<DbColumnMeta> columns = columnMapper.selectList(new LambdaQueryWrapper<DbColumnMeta>()
                .eq(DbColumnMeta::getDatasourceId, datasourceId).eq(DbColumnMeta::getSnapshotId, snapshotId));
        List<DbTableMeta> tables = tableMapper.selectList(new LambdaQueryWrapper<DbTableMeta>()
                .eq(DbTableMeta::getDatasourceId, datasourceId).eq(DbTableMeta::getSnapshotId, snapshotId));
        Map<Long, DbTableMeta> tablesById = tables.stream().collect(java.util.stream.Collectors.toMap(DbTableMeta::getId, item -> item));
        Map<String, DbColumnMeta> columnsByIdentity = new LinkedHashMap<>();
        Map<Long, DbTableMeta> tableByColumn = new LinkedHashMap<>();
        for (DbColumnMeta column : columns) {
            DbTableMeta table = tablesById.get(column.getTableMetaId());
            if (table == null) continue;
            Source source = new Source(datasourceId, snapshotId, datasource.getDatabaseName(), table, column, snapshot);
            columnsByIdentity.put(identityHash(source), column);
            tableByColumn.put(column.getId(), table);
        }
        for (FieldSemanticDefinition definition : definitions) {
            FieldSemanticBinding current = definition.getCurrentBindingId() == null
                    ? null : bindingMapper.selectById(definition.getCurrentBindingId());
            DbColumnMeta newColumn = columnsByIdentity.get(definition.getIdentityHash());
            if (current != null && !snapshotId.equals(current.getSnapshotId())) {
                if (newColumn == null) {
                    current.setStatus("UNAVAILABLE");
                    current.setStatusReason("新采集快照中字段已删除、重命名或同名重建；未自动重绑");
                    bindingMapper.updateById(current);
                } else {
                    DbTableMeta newTable = tableByColumn.get(newColumn.getId());
                    Source newSource = new Source(datasourceId, snapshotId, datasource.getDatabaseName(), newTable, newColumn, snapshot);
                    if (!Objects.equals(current.getSchemaFingerprint(), fingerprint(newSource))) {
                        current.setStatus("NEEDS_REVIEW");
                        current.setStatusReason("新采集结构或依据变化；历史字段解释需要针对新来源复核");
                        bindingMapper.updateById(current);
                    }
                }
            }
            if (newColumn == null || findBinding(definition.getId(), snapshotId) != null) continue;
            DbTableMeta table = tableByColumn.get(newColumn.getId());
            if (table == null) continue;
            Source source = new Source(datasourceId, snapshotId, datasource.getDatabaseName(), table, newColumn, snapshot);
            FieldSemanticBinding binding = new FieldSemanticBinding();
            binding.setDefinitionId(definition.getId()); binding.setDatasourceId(datasourceId);
            binding.setSnapshotId(snapshotId); binding.setTableMetaId(table.getId());
            binding.setColumnMetaId(newColumn.getId()); binding.setTableName(table.getTableName());
            binding.setColumnName(newColumn.getColumnName()); binding.setSchemaFingerprint(fingerprint(source));
            boolean unavailable = forbidden(newColumn.getGovernanceStatus()) || forbidden(table.getGovernanceStatus());
            binding.setStatus(unavailable ? "UNAVAILABLE" : "PENDING_CONFIRMATION");
            binding.setStatusReason(unavailable ? "BLOCKED/DEPRECATED 资源不能建立字段语义使用资格"
                    : "新快照采集后需要逐项核对来源绑定，不继承旧审核证据");
            binding.setCreatedBy(createdBy);
            bindingMapper.insert(binding);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> glossaryOptions(Long columnMetaId, Long snapshotId, Long userId) {
        Source source = source(columnMetaId, snapshotId);
        require(userId, FIELD_VIEW, source.datasourceId());
        require(userId, "glossary:view", source.datasourceId());
        List<GlossaryTerm> terms = glossaryTermMapper.selectList(new LambdaQueryWrapper<GlossaryTerm>()
                .eq(GlossaryTerm::getStatus, "APPROVED").orderByAsc(GlossaryTerm::getId).last("LIMIT 200"));
        List<Map<String, Object>> options = new ArrayList<>();
        for (GlossaryTerm term : terms) {
            try {
                Map<String, Object> approved = glossaryWorkflow.approvedFact(term.getId());
                GlossaryTermReview review = glossaryReviewMapper.selectById(((Number) approved.get("reviewId")).longValue());
                Map<String, Object> fact = glossaryRef(term.getId(), review, source);
                options.add(Map.of("termId", fact.get("termId"), "reviewId", fact.get("reviewId"),
                        "name", stringValue(fact.get("name")) == null ? "" : stringValue(fact.get("name")),
                        "description", stringValue(fact.get("description")) == null ? "" : stringValue(fact.get("description")),
                        "synonyms", stringValue(fact.get("synonyms")) == null ? "" : stringValue(fact.get("synonyms")),
                        "dependencies", fact.get("dependencies")));
            } catch (RuntimeException ignored) {
                // Exclude unreviewed, stale, cross-source or incompletely mapped terms.
            }
        }
        return options;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> knowledgeFacts(Long datasourceId, Long snapshotId) {
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null || !Objects.equals(snapshot.getDatasourceId(), datasourceId)) {
            throw new BusinessException(409, "字段语义事实的数据源或快照归属不一致");
        }
        List<DbColumnMeta> columns = columnMapper.selectList(new LambdaQueryWrapper<DbColumnMeta>()
                .eq(DbColumnMeta::getDatasourceId, datasourceId)
                .eq(DbColumnMeta::getSnapshotId, snapshotId)
                .orderByAsc(DbColumnMeta::getTableName).orderByAsc(DbColumnMeta::getOrdinalPosition));
        List<Map<String, Object>> facts = new ArrayList<>();
        for (DbColumnMeta column : columns) {
            FieldSemanticEvaluation evaluation = evaluate(column.getId(), snapshotId);
            if (!evaluation.usable()) continue;
            FieldSemanticDefinition definition = definitionMapper.selectById(evaluation.definitionId());
            FieldSemanticBinding binding = bindingMapper.selectById(evaluation.bindingId());
            FieldSemanticRevision revision = revisionMapper.selectById(evaluation.revisionId());
            if (definition == null || binding == null || revision == null) continue;
            DbTableMeta table = tableMapper.selectById(column.getTableMetaId());
            if (table == null) continue;
            Map<String, Object> fact = new LinkedHashMap<>();
            List<Long> sourceIds = List.of(definition.getId(), revision.getId(), evaluation.reviewId(), binding.getId());
            List<String> dependencies = new ArrayList<>(knowledgeDependencies(table, column, revision));
            fact.put("sourceIds", sourceIds); fact.put("dependencies", dependencies);
            fact.put("reviewStatus", "APPROVED"); fact.put("governanceStatus", column.getGovernanceStatus());
            fact.put("contentHash", revision.getContentHash()); fact.put("dependencyHash", evaluation.dependencyHash());
            fact.put("businessName", revision.getBusinessName()); fact.put("body", knowledgeFactBody(revision));
            facts.add(fact);
        }
        return facts;
    }

    @Override @Transactional(readOnly=true)
    public Map<String,Object> reviewedContext(Long columnId,Long snapshotId){
        var evaluation=evaluate(columnId,snapshotId);
        if(!evaluation.usable())return Map.of("qualified",false);
        var source=source(columnId,snapshotId);var revision=revisionMapper.selectById(evaluation.revisionId());
        Map<String,Object> context=new TreeMap<>();context.put("qualified",true);
        context.put("sourceIds",List.of(evaluation.definitionId(),evaluation.revisionId(),evaluation.reviewId(),evaluation.bindingId()));
        context.put("contentHash",evaluation.contentHash());context.put("dependencyHash",evaluation.dependencyHash());
        context.put("dependencies",knowledgeDependencies(source.table(),source.column(),revision).stream().sorted().toList());
        context.put("body",knowledgeFactBody(revision));return context;
    }

    @Override
    @Transactional(readOnly = true)
    public void validateKnowledgeFact(Long datasourceId, Long snapshotId, Map<String, Object> marker, String body) {
        List<String> sourceIds = stringList(marker.get("sourceIds"));
        if (sourceIds.size() != 4 || !"APPROVED".equals(marker.get("reviewStatus"))) {
            throw new BusinessException("FIELD_SEMANTIC 事实缺少精确修订、来源绑定或审核依据");
        }
        if (!membershipValid(datasourceId, snapshotId, sourceIds)) {
            throw new BusinessException("FIELD_SEMANTIC 事实对应的字段解释、来源或审核已失效");
        }
        Long bindingId;
        try { bindingId = Long.valueOf(sourceIds.get(3)); }
        catch (NumberFormatException exception) { throw new BusinessException("FIELD_SEMANTIC 来源 ID 无效"); }
        FieldSemanticBinding binding = bindingMapper.selectById(bindingId);
        Source source = binding == null ? null : source(binding.getColumnMetaId(), binding.getSnapshotId());
        FieldSemanticDefinition definition = definitionMapper.selectById(Long.valueOf(sourceIds.get(0)));
        FieldSemanticRevision revision = revisionMapper.selectById(Long.valueOf(sourceIds.get(1)));
        if (source == null || definition == null || revision == null
                || !datasourceId.equals(source.datasourceId()) || !snapshotId.equals(source.snapshotId())
                || !Objects.equals(marker.get("governanceStatus"), source.column().getGovernanceStatus())
                || !Objects.equals(marker.get("contentHash"), revision.getContentHash())
                || !Objects.equals(marker.get("dependencyHash"), evaluate(source.column().getId(), snapshotId).dependencyHash())
                || !new java.util.HashSet<>(stringList(marker.get("dependencies")))
                .equals(new java.util.HashSet<>(knowledgeDependencies(source.table(), source.column(), revision)))
                || !Objects.equals(body, knowledgeFactBody(revision))) {
            throw new BusinessException("FIELD_SEMANTIC 事实正文、依赖或来源版本与权威修订不一致");
        }
    }

    private FieldSemanticWorkspaceVO toWorkspace(Source source, FieldSemanticDefinition definition,
                                                 FieldSemanticBinding binding, boolean detailAuthorized) {
        FieldSemanticWorkspaceVO vo = new FieldSemanticWorkspaceVO();
        vo.setDatasourceId(source.datasourceId()); vo.setSnapshotId(source.snapshotId());
        vo.setTableMetaId(source.table().getId()); vo.setColumnMetaId(source.column().getId());
        vo.setDatabaseName(source.databaseName()); vo.setTableName(source.table().getTableName());
        vo.setColumnName(source.column().getColumnName()); vo.setDataType(source.column().getDataType());
        vo.setRawColumnComment(source.column().getColumnComment()); vo.setGovernanceStatus(source.column().getGovernanceStatus());
        if (definition == null) {
            vo.setRowVersion(0); vo.setBindingStatus("UNBOUND"); vo.setEffectiveStatus("NO_APPROVED_EXPLANATION");
            return vo;
        }
        vo.setDefinitionId(definition.getId()); vo.setRowVersion(definition.getRowVersion());
        vo.setCurrentBindingId(definition.getCurrentBindingId());
        vo.setCurrentApprovedRevisionId(definition.getCurrentApprovedRevisionId());
        vo.setDefinitionStatus(definition.getStatus());
        vo.setBindingStatus(binding == null ? "PENDING_CONFIRMATION" : binding.getStatus());
        vo.setBindingReason(binding == null ? "该快照尚无来源绑定" : binding.getStatusReason());
        FieldSemanticRevision approved = definition.getCurrentApprovedRevisionId() == null ? null
                : revisionMapper.selectById(definition.getCurrentApprovedRevisionId());
        FieldSemanticRevision draft = definition.getWorkingRevisionId() == null ? null
                : revisionMapper.selectById(definition.getWorkingRevisionId());
        if (draft != null) vo.setWorkingStatus(draft.getStatus());
        if (draft != null && !Objects.equals(draft.getBindingId(), binding == null ? null : binding.getId())) {
            // One working pointer is retained per stable definition. Do not show or mutate a draft
            // belonging to another snapshot from this snapshot's workspace.
            draft = null;
        }
        if (draft != null) {
            vo.setWorkingRevisionId(draft.getId());
            vo.setWorkingStatus(draft.getStatus());
        }
        FieldSemanticEvaluation evaluation = evaluate(source.column().getId(), source.snapshotId());
        vo.setEffectiveStatus(evaluation.usable() ? "APPROVED"
                : "MISSING_FIELD_SEMANTIC".equals(evaluation.ruleCode()) ? "NO_APPROVED_EXPLANATION"
                : "NOT_APPLICABLE".equals(evaluation.outcome()) ? "NOT_APPLICABLE" : "NEEDS_REVIEW");
        vo.setCurrentApproved(approved);
        if (detailAuthorized) {
            vo.setDraft(draft);
            vo.setRevisions(revisionMapper.selectList(new LambdaQueryWrapper<FieldSemanticRevision>()
                    .eq(FieldSemanticRevision::getDefinitionId, definition.getId())
                    .orderByDesc(FieldSemanticRevision::getRevisionNo)));
            vo.setReviews(reviewMapper.selectList(new LambdaQueryWrapper<FieldSemanticReview>()
                    .eq(FieldSemanticReview::getDefinitionId, definition.getId())
                    .orderByDesc(FieldSemanticReview::getCreatedAt)));
            vo.setBindings(bindingMapper.selectList(new LambdaQueryWrapper<FieldSemanticBinding>()
                    .eq(FieldSemanticBinding::getDefinitionId, definition.getId())
                    .orderByDesc(FieldSemanticBinding::getSnapshotId)));
            vo.setCandidates(candidateMapper.selectList(new LambdaQueryWrapper<FieldSemanticCandidate>()
                    .eq(FieldSemanticCandidate::getDefinitionId, definition.getId())
                    .orderByDesc(FieldSemanticCandidate::getCreatedAt).last("LIMIT 20")));
        }
        return vo;
    }

    private boolean hasApplicableReview(FieldSemanticDefinition definition, FieldSemanticRevision revision,
                                        FieldSemanticBinding binding) {
        return reviewMapper.selectCount(new LambdaQueryWrapper<FieldSemanticReview>()
                .eq(FieldSemanticReview::getDefinitionId, definition.getId())
                .eq(FieldSemanticReview::getRevisionId, revision.getId())
                .eq(FieldSemanticReview::getBindingId, binding.getId())
                .in(FieldSemanticReview::getAction, "APPROVE", "BIND_CONFIRM")
                .eq(FieldSemanticReview::getContentHash, revision.getContentHash())
                .eq(FieldSemanticReview::getDependencyHash, revision.getDependencyHash())) > 0;
    }

    private Source source(Long columnMetaId, Long snapshotId) {
        if (columnMetaId == null || snapshotId == null) throw new BusinessException(400, "必须明确指定字段和快照");
        DbColumnMeta column = columnMapper.selectById(columnMetaId);
        if (column == null || !snapshotId.equals(column.getSnapshotId()) || column.getDatasourceId() == null)
            throw new BusinessException(404, "字段不属于请求指定的快照");
        DbTableMeta table = tableMapper.selectById(column.getTableMetaId());
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        Datasource datasource = datasourceMapper.selectById(column.getDatasourceId());
        if (table == null || snapshot == null || datasource == null || !column.getTableMetaId().equals(table.getId())
                || !snapshotId.equals(table.getSnapshotId()) || !column.getDatasourceId().equals(table.getDatasourceId())
                || !column.getDatasourceId().equals(snapshot.getDatasourceId())) {
            throw new BusinessException(409, "字段、表和快照的来源归属链不完整或不一致");
        }
        return new Source(datasource.getId(), snapshot.getId(), datasource.getDatabaseName(), table, column, snapshot);
    }

    private FieldSemanticDefinition findDefinition(Source source) {
        return definitionMapper.selectOne(new LambdaQueryWrapper<FieldSemanticDefinition>()
                .eq(FieldSemanticDefinition::getDatasourceId, source.datasourceId())
                .eq(FieldSemanticDefinition::getIdentityHash, identityHash(source)));
    }

    private FieldSemanticDefinition requireDefinition(Source source) {
        FieldSemanticDefinition definition = findDefinition(source);
        if (definition == null) throw new BusinessException(404, "字段尚无平台语义记录");
        return definition;
    }

    private FieldSemanticDefinition lockDefinition(FieldSemanticDefinition definition) {
        if (definition == null || definition.getId() == null) throw new BusinessException(404, "字段语义记录不存在");
        FieldSemanticDefinition locked = definitionMapper.selectForUpdate(definition.getId());
        if (locked == null) throw new BusinessException(404, "字段语义记录不存在");
        return locked;
    }

    private FieldSemanticDefinition requireDefinitionForColumn(Long columnMetaId) {
        DbColumnMeta column = columnMapper.selectById(columnMetaId);
        if (column == null || column.getDatasourceId() == null) throw new BusinessException(404, "字段不存在或缺少数据源归属");
        DbTableMeta table = tableMapper.selectById(column.getTableMetaId());
        Datasource datasource = datasourceMapper.selectById(column.getDatasourceId());
        if (table == null || datasource == null || !column.getDatasourceId().equals(table.getDatasourceId()))
            throw new BusinessException(409, "字段来源归属链不完整");
        Source source = new Source(datasource.getId(), column.getSnapshotId(), datasource.getDatabaseName(), table, column, null);
        return requireDefinition(source);
    }

    private FieldSemanticDefinition getOrCreateDefinition(Source source, Long userId) {
        FieldSemanticDefinition definition = findDefinition(source);
        if (definition != null) return definition;
        definition = new FieldSemanticDefinition();
        definition.setDatasourceId(source.datasourceId()); definition.setDatabaseName(source.databaseName());
        definition.setTableName(source.table().getTableName()); definition.setColumnName(source.column().getColumnName());
        definition.setIdentityHash(identityHash(source)); definition.setStatus(ACTIVE); definition.setRowVersion(1);
        definition.setCreatedBy(userId); definition.setUpdatedBy(userId);
        try { definitionMapper.insert(definition); }
        catch (RuntimeException exception) {
            FieldSemanticDefinition concurrent = findDefinition(source);
            if (concurrent != null) return concurrent;
            throw new BusinessException(409, "字段语义记录创建冲突，请刷新后重试");
        }
        return definition;
    }

    private FieldSemanticBinding findBinding(Long definitionId, Long snapshotId) {
        return bindingMapper.selectOne(new LambdaQueryWrapper<FieldSemanticBinding>()
                .eq(FieldSemanticBinding::getDefinitionId, definitionId)
                .eq(FieldSemanticBinding::getSnapshotId, snapshotId));
    }

    private FieldSemanticBinding getOrCreateBinding(FieldSemanticDefinition definition, Source source, Long userId) {
        FieldSemanticBinding binding = findBinding(definition.getId(), source.snapshotId());
        if (binding != null) return binding;
        boolean firstSource = bindingMapper.selectCount(new LambdaQueryWrapper<FieldSemanticBinding>()
                .eq(FieldSemanticBinding::getDefinitionId, definition.getId())) == 0;
        binding = new FieldSemanticBinding();
        binding.setDefinitionId(definition.getId()); binding.setDatasourceId(source.datasourceId());
        binding.setSnapshotId(source.snapshotId()); binding.setTableMetaId(source.table().getId());
        binding.setColumnMetaId(source.column().getId()); binding.setTableName(source.table().getTableName());
        binding.setColumnName(source.column().getColumnName()); binding.setSchemaFingerprint(fingerprint(source));
        binding.setStatus(firstSource ? VALID : "PENDING_CONFIRMATION");
        if (!firstSource) binding.setStatusReason("新快照采集后需要逐项核对来源绑定");
        binding.setCreatedBy(userId);
        try { bindingMapper.insert(binding); }
        catch (RuntimeException exception) {
            FieldSemanticBinding concurrent = findBinding(definition.getId(), source.snapshotId());
            if (concurrent != null) return concurrent;
            throw new BusinessException(409, "字段来源绑定创建冲突，请刷新后重试");
        }
        return binding;
    }

    private FieldSemanticBinding currentBinding(FieldSemanticDefinition definition, Source source, FieldSemanticBinding binding) {
        if (!Objects.equals(binding.getSchemaFingerprint(), fingerprint(source)) && !"UNAVAILABLE".equals(binding.getStatus())) {
            binding.setStatus("NEEDS_REVIEW");
            binding.setStatusReason("字段结构或采集依据已变化，需要复核");
            bindingMapper.updateById(binding);
        }
        return binding;
    }

    private FieldSemanticRevision currentWorking(FieldSemanticDefinition definition) {
        return definition.getWorkingRevisionId() == null ? null : revisionMapper.selectById(definition.getWorkingRevisionId());
    }

    private FieldSemanticRevision newRevision(FieldSemanticDefinition definition, FieldSemanticBinding binding,
                                               String businessName, String meaning, String unit, String currency,
                                               String enumSemantics, String metricDefinition, String applicability,
                                               String evidence, List<String> unresolved, List<Map<String, Object>> glossaryRefs,
                                               Long userId) {
        FieldSemanticRevision row = new FieldSemanticRevision();
        row.setDefinitionId(definition.getId()); row.setBindingId(binding.getId());
        row.setRevisionNo(nextRevisionNo(definition.getId())); row.setStatus("DRAFT");
        row.setBusinessName(trim(businessName, 200)); row.setMeaning(trim(meaning, 4000));
        row.setUnit(trim(unit, 200)); row.setCurrency(trim(currency, 100));
        row.setEnumSemantics(trim(enumSemantics, 4000)); row.setMetricDefinition(trim(metricDefinition, 4000));
        row.setApplicability(trim(applicability, 2000)); row.setEvidence(trim(evidence, 4000));
        row.setUnresolvedItemsJson(json(normalizeTextList(unresolved)));
        row.setGlossaryRefsJson(json(glossaryRefs)); row.setContentHash(contentHash(row));
        row.setDependencyHash(dependencyHash(binding, glossaryRefs)); row.setCreatedBy(userId);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    private int nextRevisionNo(Long definitionId) {
        FieldSemanticRevision latest = revisionMapper.selectOne(new LambdaQueryWrapper<FieldSemanticRevision>()
                .eq(FieldSemanticRevision::getDefinitionId, definitionId)
                .orderByDesc(FieldSemanticRevision::getRevisionNo).last("LIMIT 1"));
        return latest == null || latest.getRevisionNo() == null ? 1 : latest.getRevisionNo() + 1;
    }

    private void checkVersion(FieldSemanticDefinition definition, Integer expected) {
        if (definition == null || expected == null || !Objects.equals(expected, definition.getRowVersion())) {
            throw new BusinessException(409, "字段解释版本已变化，请刷新后重试");
        }
    }

    private void updateDefinition(FieldSemanticDefinition definition, Long bindingId, Long approvedRevisionId,
                                  Long workingRevisionId, String status, Long userId, String conflictMessage) {
        int expected = definition.getRowVersion();
        int updated = definitionMapper.update(null, new LambdaUpdateWrapper<FieldSemanticDefinition>()
                .eq(FieldSemanticDefinition::getId, definition.getId())
                .eq(FieldSemanticDefinition::getRowVersion, expected)
                .set(FieldSemanticDefinition::getCurrentBindingId, bindingId)
                .set(FieldSemanticDefinition::getCurrentApprovedRevisionId, approvedRevisionId)
                .set(FieldSemanticDefinition::getWorkingRevisionId, workingRevisionId)
                .set(FieldSemanticDefinition::getStatus, status)
                .set(FieldSemanticDefinition::getUpdatedBy, userId)
                .set(FieldSemanticDefinition::getRowVersion, expected + 1));
        if (updated != 1) throw new BusinessException(409, conflictMessage);
    }

    private void insertReview(FieldSemanticDefinition definition, FieldSemanticRevision revision,
                              FieldSemanticBinding binding, String action, String reason, Long userId,
                              Integer expectedRowVersion) {
        FieldSemanticReview review = new FieldSemanticReview();
        review.setDefinitionId(definition.getId()); review.setRevisionId(revision.getId());
        review.setBindingId(binding.getId()); review.setAction(action);
        review.setExpectedRowVersion(expectedRowVersion);
        review.setContentHash(revision.getContentHash());
        review.setDependencyHash("BIND_CONFIRM".equals(action)
                ? dependencyHash(binding, List.of()) : revision.getDependencyHash());
        review.setReason(trim(reason, 1000)); review.setOperatorId(userId); review.setCreatedAt(LocalDateTime.now());
        reviewMapper.insert(review);
    }

    private void validateBindingSource(FieldSemanticBinding binding, Source source) {
        if (binding == null || !Objects.equals(binding.getColumnMetaId(), source.column().getId())
                || !Objects.equals(binding.getSnapshotId(), source.snapshotId())
                || !Objects.equals(binding.getDatasourceId(), source.datasourceId())) {
            throw new BusinessException(409, "字段解释来源绑定与当前字段不一致");
        }
    }

    private void validateRevisionHashes(FieldSemanticRevision revision, FieldSemanticBinding binding, Source source) {
        List<Map<String, Object>> refs = parseMapList(revision.getGlossaryRefsJson());
        List<Long> reviewIds = refs.stream().map(ref -> ((Number) ref.get("reviewId")).longValue()).toList();
        if (!Objects.equals(revision.getContentHash(), contentHash(revision))
                || !Objects.equals(revision.getDependencyHash(), dependencyHash(binding, refs))
                || !normalizeGlossaryRefs(reviewIds, source).equals(refs)
                || !Objects.equals(binding.getSchemaFingerprint(), fingerprint(source))) {
            throw new BusinessException(409, "字段解释内容、完整依赖或来源已变化，请重新核对");
        }
    }

    private void requireUsableSource(Source source) {
        if (forbidden(source.column().getGovernanceStatus()) || forbidden(source.table().getGovernanceStatus())) {
            throw new BusinessException(409, "BLOCKED/DEPRECATED 字段或表不能建立语义事实");
        }
    }

    private boolean forbidden(String status) { return "BLOCKED".equals(status) || "DEPRECATED".equals(status); }

    private static final class BindingRequiresReviewException extends BusinessException {
        private BindingRequiresReviewException(String message) { super(409, message); }
    }

    private void requireWritable(Long userId, Long datasourceId) {
        require(userId, FIELD_MANAGE, datasourceId);
        require(userId, KNOWLEDGE_MANAGE, datasourceId);
    }

    private void require(Long userId, String function, Long datasourceId) {
        adminGuard.requireDatasourceFunction(userId, function, datasourceId);
    }

    private Map<String, Object> candidateInput(Source source, Long userId) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("tableName", source.table().getTableName()); field.put("columnName", source.column().getColumnName());
        field.put("dataType", source.column().getDataType()); field.put("rawCommentPendingConfirmation", source.column().getColumnComment());
        field.put("nullable", source.column().getIsNullable()); field.put("primaryKey", source.column().getIsPrimaryKey());
        List<DbColumnMeta> columns = columnMapper.selectList(new LambdaQueryWrapper<DbColumnMeta>()
                .eq(DbColumnMeta::getSnapshotId, source.snapshotId())
                .eq(DbColumnMeta::getTableMetaId, source.table().getId())
                .eq(DbColumnMeta::getDatasourceId, source.datasourceId())
                .orderByAsc(DbColumnMeta::getOrdinalPosition))
                .stream()
                .filter(column -> !forbidden(column.getGovernanceStatus()))
                .toList();
        List<Map<String, Object>> sameTable = columns.stream().map(column -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tableName", column.getTableName()); item.put("columnName", column.getColumnName());
            item.put("dataType", column.getDataType()); item.put("rawCommentPendingConfirmation", column.getColumnComment());
            item.put("nullable", column.getIsNullable()); item.put("primaryKey", column.getIsPrimaryKey());
            return item;
        }).toList();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("datasourceId", source.datasourceId()); input.put("snapshotId", source.snapshotId());
        input.put("field", field); input.put("sameTableColumns", sameTable);
        input.put("compliantRelations", compliantRelations(source));
        input.put("reviewedGlossary", reviewedGlossaryContext(source, userId));
        return input;
    }

    private List<Map<String, Object>> compliantRelations(Source source) {
        List<TableRelation> relations = tableRelationMapper.selectList(new LambdaQueryWrapper<TableRelation>()
                .eq(TableRelation::getDatasourceId, source.datasourceId())
                .eq(TableRelation::getSnapshotId, source.snapshotId())
                .and(wrapper -> wrapper.eq(TableRelation::getSourceTable, source.table().getTableName())
                        .or().eq(TableRelation::getTargetTable, source.table().getTableName())));
        List<Map<String, Object>> result = new ArrayList<>();
        for (TableRelation relation : relations) {
            boolean confirmed = TableRelation.TYPE_FK.equals(relation.getRelationType())
                    || (TableRelation.TYPE_MANUAL.equals(relation.getRelationType())
                    && "CONFIRMED".equals(relation.getReviewStatus()));
            if (!confirmed) continue;
            DbColumnMeta sourceColumn = findColumn(source, relation.getSourceTable(), relation.getSourceColumn());
            DbColumnMeta targetColumn = findColumn(source, relation.getTargetTable(), relation.getTargetColumn());
            DbTableMeta sourceTable = findTable(source, relation.getSourceTable());
            DbTableMeta targetTable = findTable(source, relation.getTargetTable());
            if (sourceColumn == null || targetColumn == null || sourceTable == null || targetTable == null
                    || forbidden(sourceColumn.getGovernanceStatus()) || forbidden(targetColumn.getGovernanceStatus())
                    || forbidden(sourceTable.getGovernanceStatus()) || forbidden(targetTable.getGovernanceStatus())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("relationId", relation.getId()); item.put("relationType", relation.getRelationType());
            item.put("reviewStatus", relation.getReviewStatus());
            item.put("source", relation.getSourceTable() + "." + relation.getSourceColumn());
            item.put("target", relation.getTargetTable() + "." + relation.getTargetColumn());
            result.add(item);
        }
        return result;
    }

    private DbColumnMeta findColumn(Source source, String tableName, String columnName) {
        if (!StringUtils.hasText(tableName) || !StringUtils.hasText(columnName)) return null;
        return columnMapper.selectOne(new LambdaQueryWrapper<DbColumnMeta>()
                .eq(DbColumnMeta::getDatasourceId, source.datasourceId())
                .eq(DbColumnMeta::getSnapshotId, source.snapshotId())
                .eq(DbColumnMeta::getTableName, tableName)
                .eq(DbColumnMeta::getColumnName, columnName));
    }

    private DbTableMeta findTable(Source source, String tableName) {
        if (!StringUtils.hasText(tableName)) return null;
        return tableMapper.selectOne(new LambdaQueryWrapper<DbTableMeta>()
                .eq(DbTableMeta::getDatasourceId, source.datasourceId())
                .eq(DbTableMeta::getSnapshotId, source.snapshotId())
                .eq(DbTableMeta::getTableName, tableName));
    }

    private List<Map<String, Object>> normalizeGlossaryRefs(List<Long> reviewIds, Source source, Long userId) {
        if (reviewIds == null || reviewIds.isEmpty()) return List.of();
        require(userId, "glossary:view", source.datasourceId());
        return normalizeGlossaryRefs(reviewIds, source);
    }

    private List<Map<String, Object>> normalizeGlossaryRefs(List<Long> reviewIds, Source source) {
        if (reviewIds == null || reviewIds.isEmpty()) return List.of();
        if (reviewIds.size() > 30 || reviewIds.stream().distinct().count() != reviewIds.size()) {
            throw new BusinessException(400, "术语审核记录列表重复或超过 30 项");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Long reviewId : reviewIds) {
            GlossaryTermReview review = glossaryReviewMapper.selectById(reviewId);
            if (review == null || !"APPROVED".equals(review.getAction())) throw new BusinessException(409, "术语审核记录不存在或不是已通过状态");
            result.add(glossaryRef(review.getTermId(), review, source));
        }
        return result.stream().sorted(java.util.Comparator.comparing(item -> String.valueOf(item.get("termId")))).toList();
    }

    private List<Map<String, Object>> candidateGlossaryRefs(FieldSemanticCandidate candidate, Long operatorId) {
        try {
            var root = objectMapper.readTree(candidate.getInputJson());
            var facts = root.path("reviewedGlossary");
            if (!facts.isArray()) return List.of();
            List<Long> reviewIds = new ArrayList<>();
            facts.forEach(fact -> reviewIds.add(fact.path("reviewId").asLong()));
            FieldSemanticBinding binding = bindingMapper.selectById(candidate.getBindingId());
            if (binding == null) throw new BusinessException(409, "AI 候选来源绑定已失效");
            Source source = source(binding.getColumnMetaId(), binding.getSnapshotId());
            return normalizeGlossaryRefs(reviewIds, source, operatorId);
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException("AI 候选审核术语来源记录损坏"); }
    }

    private List<Map<String, Object>> reviewedGlossaryContext(Source source, Long userId) {
        List<Long> visible = capabilityService.responsibleDatasourcesWithFunction(userId, "glossary:view")
                .stream().map(ref -> ref.id()).toList();
        if (!visible.contains(source.datasourceId())) return List.of();
        List<GlossaryTerm> terms = glossaryTermMapper.selectList(new LambdaQueryWrapper<GlossaryTerm>()
                .eq(GlossaryTerm::getStatus, "APPROVED").orderByAsc(GlossaryTerm::getId).last("LIMIT 200"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (GlossaryTerm term : terms) {
            try {
                Map<String, Object> current = glossaryWorkflow.approvedFact(term.getId());
                Long reviewId = ((Number) current.get("reviewId")).longValue();
                GlossaryTermReview review = glossaryReviewMapper.selectById(reviewId);
                Map<String, Object> ref = glossaryRef(term.getId(), review, source);
                List<String> dependencies = stringList(ref.get("dependencies"));
                if (dependencies.stream().anyMatch(item -> item.startsWith("table:" + source.table().getTableName() + ".")
                        || item.startsWith("column:" + source.table().getTableName() + "."))) result.add(ref);
            } catch (RuntimeException ignored) {
                // Invalid, cross-source or stale glossary facts are excluded from AI input.
            }
        }
        return result;
    }

    private Map<String, Object> glossaryRef(Long termId, GlossaryTermReview review, Source source) {
        if (review == null || !termId.equals(review.getTermId()) || !"APPROVED".equals(review.getAction())) {
            throw new BusinessException(409, "术语审核凭据已变化");
        }
        Map<String, Object> current = glossaryWorkflow.approvedFact(termId);
        if (!Objects.equals(String.valueOf(current.get("reviewId")), String.valueOf(review.getId()))
                || !Objects.equals(String.valueOf(current.get("revision")), String.valueOf(review.getRevision()))
                || !Objects.equals(String.valueOf(current.get("contentHash")), review.getContentHash())) {
            throw new BusinessException(409, "术语已产生新修订或审核记录，请重新选择");
        }
        Object rawColumns = current.get("columns");
        if (!(rawColumns instanceof List<?> columns) || columns.isEmpty()) throw new BusinessException(409, "术语缺少完整字段映射");
        java.util.TreeSet<String> dependencies = new java.util.TreeSet<>();
        boolean includesTarget = false;
        for (Object raw : columns) {
            if (!(raw instanceof TermColumnVO column) || !source.datasourceId().equals(column.datasourceId())
                    || !source.snapshotId().equals(column.snapshotId()) || column.tableId() == null
                    || column.columnId() == null) {
                throw new BusinessException(409, "术语依赖跨源或不属于当前快照，不能裁剪使用");
            }
            DbColumnMeta dependencyColumn = columnMapper.selectById(column.columnId());
            DbTableMeta dependencyTable = tableMapper.selectById(column.tableId());
            if (dependencyColumn == null || dependencyTable == null
                    || !source.datasourceId().equals(dependencyColumn.getDatasourceId())
                    || !source.snapshotId().equals(dependencyColumn.getSnapshotId())
                    || !column.tableId().equals(dependencyColumn.getTableMetaId())
                    || !column.tableId().equals(dependencyTable.getId())
                    || !source.datasourceId().equals(dependencyTable.getDatasourceId())
                    || !source.snapshotId().equals(dependencyTable.getSnapshotId())
                    || !Objects.equals(column.tableName(), dependencyTable.getTableName())
                    || !Objects.equals(column.tableName(), dependencyColumn.getTableName())
                    || !Objects.equals(column.columnName(), dependencyColumn.getColumnName())) {
                throw new BusinessException(409, "术语依赖字段与其来源快照归属不一致，不能裁剪使用");
            }
            if (forbidden(dependencyColumn.getGovernanceStatus()) || forbidden(dependencyTable.getGovernanceStatus())) {
                throw new BusinessException(409, "术语完整映射包含 BLOCKED/DEPRECATED 字段或表，不能作为 AI 依据");
            }
            dependencies.add("table:" + column.tableName());
            dependencies.add("column:" + column.tableName() + "." + column.columnName());
            if (column.columnId().equals(source.column().getId())) includesTarget = true;
        }
        if (!includesTarget) throw new BusinessException(409, "所选术语未映射到当前字段");
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("termId", termId); fact.put("reviewId", review.getId()); fact.put("revision", review.getRevision());
        fact.put("contentHash", review.getContentHash()); fact.put("name", current.get("displayName") == null ? current.get("name") : current.get("displayName"));
        fact.put("description", current.get("description")); fact.put("synonyms", current.get("synonyms"));
        fact.put("dependencies", new ArrayList<>(dependencies));
        return fact;
    }

    private Long bindingColumn(Long bindingId) {
        FieldSemanticBinding binding = bindingMapper.selectById(bindingId);
        return binding == null ? null : binding.getColumnMetaId();
    }

    private String identityHash(Source source) {
        return identityHash(source.datasourceId(), source.databaseName(),
                source.table().getTableName(), source.column().getColumnName());
    }

    private String identityHash(Long datasourceId, String databaseName, String tableName, String columnName) {
        return sha256(canonical(List.of(datasourceId, databaseName, tableName, columnName)));
    }

    private String fingerprint(Source source) {
        Map<String, Object> values = new TreeMap<>();
        values.put("database", source.databaseName()); values.put("table", source.table().getTableName());
        values.put("column", source.column().getColumnName()); values.put("type", source.column().getDataType());
        values.put("nullable", source.column().getIsNullable()); values.put("default", source.column().getColumnDefault());
        values.put("primaryKey", source.column().getIsPrimaryKey()); values.put("rawComment", source.column().getColumnComment());
        values.put("tableType", source.table().getTableType()); values.put("tableComment", source.table().getTableComment());
        values.put("columnGovernance", source.column().getGovernanceStatus());
        values.put("tableGovernance", source.table().getGovernanceStatus());
        return sha256(canonical(values));
    }

    private String contentHash(FieldSemanticRevision row) {
        Map<String, Object> content = new TreeMap<>();
        content.put("businessName", row.getBusinessName()); content.put("meaning", row.getMeaning());
        content.put("unit", row.getUnit()); content.put("currency", row.getCurrency());
        content.put("enumSemantics", row.getEnumSemantics()); content.put("metricDefinition", row.getMetricDefinition());
        content.put("applicability", row.getApplicability()); content.put("evidence", row.getEvidence());
        content.put("unresolved", parseStringList(row.getUnresolvedItemsJson()));
        content.put("glossaryRefs", parseMapList(row.getGlossaryRefsJson()));
        return sha256(canonical(content));
    }

    private String dependencyHash(FieldSemanticBinding binding, List<Map<String, Object>> refs) {
        return sha256(canonical(Map.of("snapshotId", binding.getSnapshotId(), "tableMetaId", binding.getTableMetaId(),
                "columnMetaId", binding.getColumnMetaId(), "fingerprint", binding.getSchemaFingerprint(), "glossaryRefs", refs)));
    }

    private List<String> knowledgeDependencies(DbTableMeta table, DbColumnMeta column, FieldSemanticRevision revision) {
        java.util.TreeSet<String> dependencies = new java.util.TreeSet<>();
        dependencies.add("table:" + table.getTableName());
        dependencies.add("column:" + table.getTableName() + "." + column.getColumnName());
        for (Map<String, Object> ref : parseMapList(revision.getGlossaryRefsJson())) {
            dependencies.addAll(stringList(ref.get("dependencies")));
        }
        return new ArrayList<>(dependencies);
    }

    private String knowledgeFactBody(FieldSemanticRevision revision) {
        List<String> lines = new ArrayList<>();
        lines.add("- 业务名称: " + plain(revision.getBusinessName()));
        lines.add("- 字段含义: " + plain(revision.getMeaning()));
        addKnowledgeLine(lines, "单位", revision.getUnit());
        addKnowledgeLine(lines, "币种", revision.getCurrency());
        addKnowledgeLine(lines, "枚举含义", revision.getEnumSemantics());
        addKnowledgeLine(lines, "指标口径", revision.getMetricDefinition());
        addKnowledgeLine(lines, "适用限制", revision.getApplicability());
        lines.add("- 业务依据: " + plain(revision.getEvidence()));
        List<String> unresolved = parseStringList(revision.getUnresolvedItemsJson());
        if (!unresolved.isEmpty()) lines.add("- 待确认: " + String.join("；", unresolved.stream().map(this::plain).toList()));
        return String.join("\n", lines);
    }

    private void addKnowledgeLine(List<String> lines, String label, String value) {
        if (StringUtils.hasText(value)) lines.add("- " + label + ": " + plain(value));
    }

    private String plain(String value) {
        return (value == null ? "" : value).replaceAll("[\\r\\n\\t]+", " ")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").strip();
    }

    private String canonical(Object value) {
        try { return objectMapper.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value); }
        catch (Exception exception) { throw new BusinessException("字段解释依据序列化失败"); }
    }

    private String json(Object value) { return canonical(value); }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    private List<String> normalizeTextList(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 50) throw new BusinessException(400, "待确认项最多填写 50 条");
        return values.stream().filter(StringUtils::hasText).map(item -> trim(item, 500)).distinct().toList();
    }

    private List<String> readStringList(Object value) {
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        return List.of();
    }

    private List<String> parseStringList(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception exception) { return List.of(); }
    }

    private List<Map<String, Object>> parseMapList(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception exception) { throw new BusinessException("字段解释依赖记录损坏"); }
    }

    private void requireText(String value, int max, String label) {
        if (!StringUtils.hasText(value) || value.strip().length() > max) throw new BusinessException(400, label + "不能为空且不能超过 " + max + " 字");
    }

    private String trim(String value, int max) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.strip();
        if (normalized.length() > max) throw new BusinessException(400, "字段解释文本超过允许长度");
        return normalized;
    }

    private String stringValue(Object value) { return value == null ? null : String.valueOf(value); }

    private FieldSemanticEvaluation evaluation(String outcome, String code, String reason, Long definitionId,
                                               Long bindingId, Long revisionId, Long reviewId,
                                               String contentHash, String dependencyHash, String evidenceHash) {
        return new FieldSemanticEvaluation(outcome, code, reason, definitionId, bindingId, revisionId, reviewId,
                contentHash, dependencyHash, evidenceHash);
    }

    private String evidenceHash(Source source, FieldSemanticDefinition definition, FieldSemanticBinding binding,
                                FieldSemanticRevision revision, FieldSemanticReview review) {
        return sha256(canonical(List.of(tagCodes(source.column().getId()),source.snapshotId(), source.column().getId(),
                definition == null ? "" : definition.getId(), binding == null ? "" : binding.getId(),
                binding == null ? "" : binding.getSchemaFingerprint(), revision == null ? "" : revision.getId(),
                review == null ? "" : review.getId(), revision == null ? "" : revision.getContentHash(),
                review == null ? (revision == null ? "" : revision.getDependencyHash()) : review.getDependencyHash())));
    }

    private List<String> tagCodes(Long columnId){return fieldTags.selectList(new LambdaQueryWrapper<com.dataocean.module.fieldtag.entity.po.FieldTag>()
            .eq(com.dataocean.module.fieldtag.entity.po.FieldTag::getColumnMetaId,columnId)).stream().map(com.dataocean.module.fieldtag.entity.po.FieldTag::getTagCode)
            .filter(Objects::nonNull).distinct().sorted().toList();}

    private FieldSemanticWorkspaceVO workspaceAfterMutation(Long columnMetaId, Long snapshotId) {
        Source source = source(columnMetaId, snapshotId);
        FieldSemanticDefinition definition = findDefinition(source);
        FieldSemanticBinding binding = definition == null ? null : findBinding(definition.getId(), snapshotId);
        if (binding != null) binding = currentBinding(definition, source, binding);
        return toWorkspace(source, definition, binding, false);
    }

    private List<String> stringList(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private record Source(Long datasourceId, Long snapshotId, String databaseName,
                          DbTableMeta table, DbColumnMeta column, MetadataSnapshot snapshot) { }
}
