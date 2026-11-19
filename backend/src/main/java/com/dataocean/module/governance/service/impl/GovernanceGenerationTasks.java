package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceNotes;
import com.dataocean.module.datasource.service.GovernanceNotes.Note;
import com.dataocean.module.governance.entity.dto.GenerateScopeDTO;
import com.dataocean.module.governance.entity.po.GovernanceEvidencePO;
import com.dataocean.module.governance.entity.po.GovernanceGenerationPO;
import com.dataocean.module.governance.entity.po.GovernanceModelCallPO;
import com.dataocean.module.governance.entity.po.GovernanceSuggestionPO;
import com.dataocean.module.governance.entity.vo.GenerationAcceptedVO;
import com.dataocean.module.governance.entity.vo.GenerationDetailVO;
import com.dataocean.module.governance.entity.vo.GenerationNoteVO;
import com.dataocean.module.governance.entity.vo.GenerationPreviewVO;
import com.dataocean.module.governance.entity.vo.GenerationSuggestionVO;
import com.dataocean.module.governance.entity.vo.GenerationTargetVO;
import com.dataocean.module.governance.mapper.GovernanceEvidenceMapper;
import com.dataocean.module.governance.mapper.GovernanceGenerationMapper;
import com.dataocean.module.governance.mapper.GovernanceGenerationRecords;
import com.dataocean.module.governance.mapper.GovernanceModelCallMapper;
import com.dataocean.module.governance.mapper.GovernanceSuggestionMapper;
import com.dataocean.module.governance.service.GovernanceGenerationService;
import com.dataocean.module.governance.service.GovernanceGenerationWire;
import com.dataocean.module.governance.service.GovernanceTarget;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ReviewService;
import com.dataocean.module.iam.service.ServiceActorPermission;
import com.dataocean.module.model.service.ModelInvocation;
import com.dataocean.module.model.service.ModelRegistry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Java 持有权威任务和依据，Python 执行实际生成。
 */
@Service
@RequiredArgsConstructor
public class GovernanceGenerationTasks implements GovernanceGenerationService {

    /**
     * 首段仅做表说明，字段生成依赖后续节点。
     */
    private static final List<String> TABLE_PATHS = List.of("businessName", "purpose", "rowGrain", "rowIdentity",
            "dataScope", "timeSemantics", "updatePolicy", "usageNotes", "qualityNotes");

    /**
     * 仅登记预览中明确可见的、有界同源资料输入。
     */
    private static final int NOTE_LIMIT = 16;

    /**
     * 每笔模型请求限制附件二进制大小和合计传输。
     */
    private static final int MAX_ATTACHMENT_BYTES = 512 * 1024;

    /**
     * 一次表说明受理后的最大活动窗口，包含排队和模型调用。
     */
    private static final int TASK_SECONDS = 180;

    /**
     * 工作者可提交的业务与模型故障类型。
     */
    private static final Set<String> FAILURES = Set.of("STALE_INPUT", "CONTEXT_UNAVAILABLE", "MODEL_UNAVAILABLE",
            "MODEL_AUTH", "MODEL_RATE_LIMIT", "MODEL_FORMAT", "MODEL_TIMEOUT", "MODEL_RESULT_UNKNOWN");

    /**
     * 当前选中可用于负责治理模型的数据源清单，来源只能是服务端配置。
     */
    @Value("${dataocean.governance-ai.model-source-ids:}")
    private String modelSourceIds;

    /**
     * 本地可以只启用人工功能。
     */
    @Value("${dataocean.governance-ai.enabled:false}")
    private boolean enabled;

    /**
     * 存储持久任务和并发派发状态。
     */
    private final GovernanceGenerationMapper tasks;

    /**
     * 保存完整固定输入与目标范围。
     */
    private final GovernanceGenerationRecords records;

    /**
     * 保留模型原始业务建议。
     */
    private final GovernanceSuggestionMapper suggestions;

    /**
     * 模型成功回执与实际配置快照。
     */
    private final GovernanceModelCallMapper modelCalls;

    /**
     * 按业务节点固定配置修订并受控取用模型凭据。
     */
    private final ModelRegistry models;

    /**
     * 保存实际引用段落或附件的确定出处。
     */
    private final GovernanceEvidenceMapper evidenceRows;

    /**
     * 固定结构来源和在途版本状态。
     */
    private final GovernanceCatalog catalog;

    /**
     * 当前来源及原始资料定位。
     */
    private final GovernanceNotes notes;

    /**
     * 治理条目当前可用性和来源边界。
     */
    private final GovernanceEntries entries;

    /**
     * 当前允许写入的明确治理字段。
     */
    private final GovernanceContent content;

    /**
     * 原始建议的出处核对与后续披露。
     */
    private final GovernanceEvidence evidence;

    /**
     * 在有真实任务接受后推进实际来源复核。
     */
    private final GovernanceImpacts impacts;

    /**
     * 当前季度源和原始资料读取的业务授权。
     */
    private final PermissionService permissions;

    /**
     * 独立执行器核对实际发起人实时资格。
     */
    private final ServiceActorPermission actors;

    /**
     * 普通命令沿用公共防重与审计机制。
     */
    private final MutationService commands;

    /**
     * 签发绑定预览时的确切业务输入凭据。
     */
    private final ReviewService reviews;

    /**
     * 保存任务接受的真实平台操作。
     */
    private final AuditService audit;

    /**
     * 发布原始任务接收事实。
     */
    private final GovernanceEvents events;

    /**
     * 读取和编码明确的候选数据。
     */
    private final JsonCodec json;

    /**
     * 预览真实模型可自动取用的业务资料。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GenerationPreviewVO preview(long sourceId, long object, GenerateScopeDTO input) {
        permissions.require("governance.suggest");
        var source = catalog.source(sourceId, false);
        var scope = scope(source, object, input);
        models.requireBinding(ModelRegistry.TABLE_DESCRIPTION);
        var review = reviews.preview("governance.suggest", scope.context().target().commandTarget(),
                scope.context().item() == null ? null : scope.context().item().getVersion(), signature(scope),
                List.of(), List.of(), List.of());
        return new GenerationPreviewVO(Long.toString(sourceId), Long.toString(object),
                permissions.has("business-notes.view") ? scope.notes().stream()
                        .map(note -> noteView(note, "REGISTERED")).toList() : null,
                permissions.has("business-notes.view") ? "VISIBLE" : "RESTRICTED", review.getReviewToken());
    }

    /**
     * 接受任务时固定输入、条目版本、审计及可靠派发。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GenerationAcceptedVO accept(long sourceId, long object, GenerateScopeDTO input, String command) {
        var target = GovernanceTarget.table(sourceId, object);
        MutationVO saved = commands.execute("governance.suggest", command, target.commandTarget(), input, null,
                false, false, () -> acceptLocked(sourceId, object, input, command));
        var task = require(saved.getTargetId(), sourceId);
        return new GenerationAcceptedVO(task.getRequestId(), task.getCommandId(), task.getState(), task.getVersion());
    }

    /**
     * 发起人和可处理该目标的编辑或审核人按当前权限回读结果。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GenerationDetailVO detail(long source, String requestId) {
        permissions.require("governance.view");
        var task = require(requestId, source);
        boolean owner = task.getRequestedBy() == UserContext.getUser() && permissions.has("governance.suggest");
        if (!owner && !permissions.has("governance.edit") && !permissions.has("governance.confirm")) {
            // 无权读取此任务时不披露是否存在。
            throw BizIllegalException.missing();
        }
        var target = records.target(task.getRequestId());
        var input = records.inputs(task.getRequestId());
        boolean showNotes = permissions.has("business-notes.view");
        var suggestion = findSuggestion(task.getRequestId());
        GenerationSuggestionVO detail = null;
        if (suggestion != null && (showNotes || input.stream().noneMatch(row -> "USED".equals(row.state())))) {
            // 引用正文产生的候选保持与资料原文相同的披露边界。
            detail = new GenerationSuggestionVO(Long.toString(suggestion.getId()),
                    json.mapper().readTree(suggestion.getContent()), suggestionEvidence(suggestion.getId())
                            .stream().map(evidence::view).toList(),
                    permissions.has("diagnostics.view") ? Long.toString(suggestion.getModelCallId()) : null);
        }
        var targetView = new GenerationTargetVO(GovernanceTarget.table(source, task.getObjectId()).view(),
                task.getState(), entries.basis(task.getSnapshotId(), target.objectRevisionId(), null),
                detail, task.getFailureCode());
        var supplied = showNotes ? input.stream()
                .map(row -> noteView(notes.revision(source, row.noteId(), row.noteRevisionId()), row.state())).toList() : null;
        return new GenerationDetailVO(task.getRequestId(), task.getCommandId(), Long.toString(source),
                Long.toString(task.getObjectId()), Long.toString(task.getRequestedBy()), task.getState(),
                task.getVersion(), Times.instant(task.getStartedAt()), Times.instant(task.getEndedAt()),
                task.getFailureCode(), List.of(targetView), supplied);
    }

    /**
     * Python 工作者真正执行前只取仍符合当前资格的固定任务输入。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GovernanceGenerationWire.Context context(String requestId, int runVersion) {
        var task = require(requestId, null);
        if (task.getRunVersion() != runVersion || terminal(task.getState())) {
            // 老任务或已终结的代次不能再次取用资料。
            throw stale();
        }
        var current = eligible(task, catalog.source(task.getSourceId(), false));
        var inputs = inputs(task);
        var attachments = new ArrayList<GovernanceGenerationWire.Note>();
        int remaining = 4 * MAX_ATTACHMENT_BYTES;
        for (var note : inputs) {
            // 仅选择受控预算内的原件，较大原件保留在来源记录供人工阅读。
            var files = new ArrayList<GovernanceGenerationWire.Attachment>();
            for (var file : note.files()) {
                if (file.byteSize() <= MAX_ATTACHMENT_BYTES && file.byteSize() <= remaining) {
                    // 原件按当前已核验的确切历史修订读取。
                    remaining -= file.byteSize();
                    files.add(new GovernanceGenerationWire.Attachment(Long.toString(file.id()), file.name(),
                            Base64.getEncoder().encodeToString(notes.attachment(note, file.id()))));
                }
            }
            attachments.add(new GovernanceGenerationWire.Note(Long.toString(note.noteId()), note.body(), files));
        }
        var origin = current.entry().observation().structure().object();
        return new GovernanceGenerationWire.Context(
                (Objects.toString(origin.schema(), "").isBlank() ? "" : origin.schema() + ".") + origin.name(),
                origin.comment(),
                current.entry().columns().stream().limit(100).map(column -> new GovernanceGenerationWire.Column(
                        column.name(), column.nativeType(), column.sourceComment())).toList(),
                json.strings(records.target(task.getRequestId()).contentPaths()), attachments);
    }

    /**
     * 模型外发前固定领取，过程崩溃后从原回执核实，避免重复发送。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ModelInvocation authorizeModel(String requestId, int runVersion) {
        var initial = require(requestId, null);
        var source = catalog.source(initial.getSourceId(), true);
        var task = records.lock(initial.getRequestId());
        int remaining = (int) secondsRemaining(task);
        if (task.getRunVersion() != runVersion || terminal(task.getState()) || remaining < 1) {
            // 仅当前仍有效的执行代次可领取配置。
            throw stale();
        }
        if (task.getModelStartedAt() != null) {
            // 已发出许可的请求可能已由提供方处理，不重复外发。
            throw new BizIllegalException(ErrorCode.EXECUTION_UNCERTAIN, "模型调用结果待核查");
        }
        eligible(task, source);
        inputs(task);
        var invocation = models.invocation(task.getModelRevisionId(), remaining);
        var update = new GovernanceGenerationPO();
        update.setRequestId(task.getRequestId());
        update.setModelStartedAt(LocalDateTime.now(ZoneOffset.UTC));
        tasks.updateById(update);
        return invocation;
    }

    /**
     * 领取持久的一条接收或回读任务，外部调用不得占据平台事务。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GovernanceGenerationPO claim() {
        var task = records.next();
        if (task != null) {
            // 崩溃或迟到的旧回读不能替当前租约写结果。
            task.setLeaseToken(UUID.randomUUID().toString());
            records.claim(task.getRequestId(), task.getLeaseToken());
        }
        return task;
    }

    /**
     * 根据真实队列状态回写业务任务，最终成果再核全部原资格和出处。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void advance(GovernanceGenerationPO claimed, GovernanceGenerationWire.Status status) {
        var source = catalog.source(claimed.getSourceId(), true);
        var current = records.lock(claimed.getRequestId());
        if (current == null || !Objects.equals(current.getLeaseToken(), claimed.getLeaseToken())
                || terminal(current.getState())) {
            // 另一个背景回读已经接管或结束此任务。
            return;
        }
        if (status == null) {
            // 外部接口未能确认时按同一个任务和代次有界回读。
            boolean expired = secondsRemaining(current) <= 0;
            String reason = current.getModelStartedAt() == null ? "QUEUE_UNAVAILABLE" : "MODEL_RESULT_UNKNOWN";
            advanceState(current, expired ? "FAILED" : current.getState(), expired ? reason : null, 3);
            return;
        }
        if (status.runVersion() != current.getRunVersion()) {
            // 执行代次错误不能让旧工作者提供成果。
            advanceState(current, "FAILED", "STALE_INPUT", 0);
            return;
        }
        if ("SUCCEEDED".equals(status.state()) && status.result() != null) {
            // 完整权限和事实再次在源锁事务内核对，模型结果不会覆盖人工定义。
            try {
                var entry = eligible(current, source);
                var inputs = inputs(current);
                finish(current, entry, inputs, status.result());
                advanceState(current, "SUCCEEDED", null, 0);
            } catch (BizIllegalException error) {
                // 资格变化使旧结果不得进入业务建议。
                advanceState(current, "FAILED", "STALE_INPUT", 0);
            }
            return;
        }
        String state = Set.of("QUEUED", "RUNNING").contains(status.state()) ? status.state() : "FAILED";
        boolean expired = secondsRemaining(current) <= 0;
        if (expired && !"FAILED".equals(state)) {
            // 长时间没有执行终态时显式结束本次生成。
            advanceState(current, "FAILED",
                    current.getModelStartedAt() == null ? "MODEL_TIMEOUT" : "MODEL_RESULT_UNKNOWN", 0);
        } else {
            advanceState(current, state, "FAILED".equals(state) ? failure(status.failureCode()) : null, 1);
        }
    }

    /**
     * 获取建议真正对应的历史结果，供显式写入时校验。
     */
    public GovernanceSuggestionPO adopt(long suggestionId, GovernanceEntries.Context current) {
        var suggestion = suggestions.selectById(suggestionId);
        if (suggestion == null || current.item() == null || !suggestion.getItemId().equals(current.item().getId())) {
            // 同名目标和他源建议都不可复用。
            throw BizIllegalException.missing();
        }
        var task = require(suggestion.getRequestId(), current.target().source());
        eligible(task, catalog.source(current.target().source(), false));
        var inputs = inputs(task);
        if (!inputs.isEmpty()) {
            permissions.require("business-notes.view");
        }
        return suggestion;
    }

    /**
     * 读取建议固定的依据集合，待采用时按被选内容项核对。
     */
    public List<GovernanceEvidencePO> suggestionEvidence(long id) {
        return evidenceRows.selectList(Wrappers.<GovernanceEvidencePO>lambdaQuery()
                .eq(GovernanceEvidencePO::getSuggestionId, id));
    }

    /**
     * 复用共同的快照与输入验证，保证预览和正式命令一致。
     */
    private MutationVO acceptLocked(long sourceId, long object, GenerateScopeDTO input, String command) {
        var source = catalog.source(sourceId, true);
        var scope = scope(source, object, input);
        var context = scope.context();
        reviews.verify("governance.suggest", context.target().commandTarget(),
                context.item() == null ? null : context.item().getVersion(), signature(scope), input.reviewToken());
        var item = entries.ensure(context.target(), context.entry().generation());

        var task = new GovernanceGenerationPO();
        task.setRequestId(UUID.randomUUID().toString());
        task.setCommandId(UUID.fromString(command).toString());
        task.setSourceId(sourceId);
        task.setObjectId(object);
        task.setSnapshotId(context.entry().observation().snapshotId());
        task.setModelRevisionId(models.requireBinding(ModelRegistry.TABLE_DESCRIPTION));
        task.setRequestedBy(UserContext.getUser());
        task.setAuthenticationVersion(UserContext.current().authenticationVersion());
        task.setState("PENDING_DISPATCH");
        task.setRunVersion(1);
        task.setVersion(1);
        tasks.insert(task);
        records.insertTarget(task.getRequestId(), item.getId(), item.getVersion(), context.structureRevision(),
                json.write(scope.paths()));
        for (var note : scope.notes()) {
            // 同一来源锁中登记准确输入，原件删除与取用只能有一方成功。
            notes.reference(sourceId, note, "GOV_TASK_INPUT", task.getRequestId());
            records.addInput(task.getRequestId(), note.noteId(), note.revisionId());
        }
        impacts.started(sourceId, item.getId());
        events.requested(sourceId, task.getRequestId(), task.getCommandId());
        audit.record(AuditWriteDTO.builder().actionCode("governance.suggest").targetType("governance_generation")
                .targetId(task.getRequestId()).targetName(context.entry().observation().structure().object().name())
                .changes(List.of(new ChangeVO("state", "实际生成状态", null, "等待派发")))
                .securityVersion(commands.auditSecurityVersion(false)).commandId(task.getCommandId()).build());
        return new MutationVO(command, "governance_generation", task.getRequestId(), 1);
    }

    /**
     * 将模型调用的真实回执、候选正文和确切来源一道持久化。
     */
    private void finish(GovernanceGenerationPO task, GovernanceEntries.Context entry, List<Note> inputs,
            GovernanceGenerationWire.Result result) {
        var definition = content.normalize(false, result.content());
        var allowed = Set.copyOf(json.strings(records.target(task.getRequestId()).contentPaths()));
        var reported = result.call();
        if (definition.isEmpty() || !allowed.containsAll(definition.propertyNames())
                || result.evidence() == null || result.usedNoteIds() == null || reported == null
                || task.getModelStartedAt() == null || task.getModelRevisionId() == null
                || !Long.toString(task.getModelRevisionId()).equals(reported.revisionId())) {
            // 模型不能提交未获准领域字段或无法验证的调用记录。
            throw BizIllegalException.invalid("模型未给出可核对的建议");
        }
        var inputMap = new HashMap<String, Note>();
        inputs.forEach(note -> inputMap.put(Long.toString(note.noteId()), note));
        var refs = new ArrayList<GovernanceEvidencePO>();
        for (var citation : result.evidence()) {
            // 异常来源必须在写调用记录之前拒绝。
            if (!inputMap.containsKey(citation.noteId()) || !definition.has(citation.contentPath())
                    || !result.usedNoteIds().contains(citation.noteId())) {
                throw stale();
            }
            refs.add(evidence.model(task.getSourceId(), inputMap.get(citation.noteId()), citation));
        }
        if (!inputMap.keySet().containsAll(result.usedNoteIds())) {
            // 模型不可增补客户端输入范围以外的资料。
            throw stale();
        }

        var call = recordCall(task, "SUCCEEDED", null, reported);

        var suggestion = new GovernanceSuggestionPO();
        suggestion.setRequestId(task.getRequestId());
        suggestion.setItemId(entry.item().getId());
        suggestion.setModelCallId(call.getId());
        suggestion.setContent(json.write(definition));
        suggestions.insert(suggestion);
        for (var ref : refs) {
            // 只关联实际校验通过的候选出处。
            ref.setSuggestionId(suggestion.getId());
            ref.setCreatedBy(task.getRequestedBy());
            evidenceRows.insert(ref);
        }
        for (var note : inputs) {
            // 任务记录保留取用、跳过和具体业务引用的差异。
            records.inputState(task.getRequestId(), note.noteId(),
                    result.usedNoteIds().contains(Long.toString(note.noteId())) ? "USED" : "SKIPPED");
        }
    }

    /**
     * 权限、源、结构及人工内容原版本需持续满足。
     */
    private GovernanceEntries.Context eligible(
            GovernanceGenerationPO task, GovernanceCatalog.Source source) {
        requireModelSource(source.id());
        var rights = actors.require(task.getRequestedBy(), task.getAuthenticationVersion(),
                "governance.view", "governance.suggest");
        if (!records.inputs(task.getRequestId()).isEmpty() && !rights.contains("business-notes.view")) {
            // 发起人失去原资料资格时停止新的外发和结果应用。
            throw BizIllegalException.denied();
        }
        var current = entries.get(source, GovernanceTarget.table(source.id(), task.getObjectId()));
        var destination = records.target(task.getRequestId());
        if (!"AVAILABLE".equals(current.availability()) || current.item() == null
                || current.item().getVersion() != destination.baseItemVersion()
                || current.structureRevision() != destination.objectRevisionId()) {
            throw stale();
        }
        return current;
    }

    /**
     * 凭据、模型目的地和可传数据源完全由服务端受控配置。
     */
    private void requireModelSource(long source) {
        if (!enabled || java.util.Arrays.stream(modelSourceIds.split(","))
                .map(String::strip).noneMatch(Long.toString(source)::equals)) {
            // 当前源未授权对指定模型发送目录和企业说明。
            throw BizIllegalException.state("当前数据源未配置治理模型传输范围");
        }
    }

    /**
     * 从原任务固定的输入检查资料版本实际是否改变。
     */
    private List<Note> inputs(GovernanceGenerationPO task) {
        return records.inputs(task.getRequestId()).stream().map(input -> {
            // 原文标题变化可以等价，正文和附件实质变化使原任务失效。
            var prior = notes.revision(task.getSourceId(), input.noteId(), input.noteRevisionId());
            if (!notes.current(task.getSourceId(), input.noteId()).equivalent(prior)) {
                throw stale();
            }
            return prior;
        }).toList();
    }

    /**
     * 创建本阶段的单一表说明生成范围。
     */
    private Scope scope(GovernanceCatalog.Source source, long object, GenerateScopeDTO input) {
        requireModelSource(source.id());
        var chosen = input.targets().get(0);
        if (!"TABLE".equals(chosen.kind()) || !Long.toString(object).equals(chosen.targetId())) {
            // 单张表说明是本阶段唯一真实 AI 能力。
            throw BizIllegalException.invalid("请选择当前对象的表说明");
        }
        var context = entries.get(source, GovernanceTarget.table(source.id(), object));
        entries.verify(context, chosen.precondition(), false);
        var paths = chosen.contentPaths() == null ? TABLE_PATHS : chosen.contentPaths();
        if (paths.isEmpty() || paths.stream().distinct().count() != paths.size() || !TABLE_PATHS.containsAll(paths)) {
            throw BizIllegalException.invalid("请选择实际可生成的表说明字段");
        }
        var available = permissions.has("business-notes.view")
                ? notes.active(source.id()).stream().limit(NOTE_LIMIT).toList() : List.<Note>of();
        return new Scope(context, paths, available);
    }

    /**
     * 核对确切结构及实质依据，标题改动仍使用同内容固定修订。
     */
    private List<Object> signature(Scope scope) {
        return List.of(scope.context().structureRevision(), scope.paths(),
                scope.notes().stream().map(note -> List.of(note.noteId(), note.body(),
                        note.files().stream().map(GovernanceNotes.File::digest).sorted().toList())).toList());
    }

    /**
     * 不可披露任务采用一致的资源不存在响应。
     */
    private GovernanceGenerationPO require(String request, Long source) {
        var task = tasks.selectById(UUID.fromString(request).toString());
        if (task == null || source != null && !source.equals(task.getSourceId())) {
            throw BizIllegalException.missing();
        }
        return task;
    }

    /**
     * 只读取该任务已真实落库的一个表说明。
     */
    private GovernanceSuggestionPO findSuggestion(String request) {
        return suggestions.selectOne(Wrappers.<GovernanceSuggestionPO>lambdaQuery()
                .eq(GovernanceSuggestionPO::getRequestId, request));
    }

    /**
     * 不含资料原文的输入快照。
     */
    private GenerationNoteVO noteView(Note note, String state) {
        return new GenerationNoteVO(Long.toString(note.noteId()), note.title(), note.revision(), state);
    }

    /**
     * 当前是否已进入不能重复执行的终态。
     */
    private boolean terminal(String state) {
        return Set.of("SUCCEEDED", "FAILED").contains(state);
    }

    /**
     * 将其他服务的故障转换成明确安全的可披露原因。
     */
    private String failure(String code) {
        return code != null && FAILURES.contains(code) ? code : "MODEL_FAILED";
    }

    /**
     * 计算排队及执行共用的剩余时长。
     */
    private static long secondsRemaining(GovernanceGenerationPO task) {
        return Duration.between(LocalDateTime.now(ZoneOffset.UTC), task.getCreatedAt().plusSeconds(TASK_SECONDS))
                .toSeconds();
    }

    /**
     * 以平台固定的任务修订记录可审计的调用元数据。
     */
    private GovernanceModelCallPO recordCall(GovernanceGenerationPO task, String state, String error,
            GovernanceGenerationWire.Call report) {
        var call = new GovernanceModelCallPO();
        call.setRequestId(task.getRequestId());
        call.setNodeCode(ModelRegistry.TABLE_DESCRIPTION);
        call.setState(state);
        call.setFailureCode(error);
        if (report != null) {
            // 回执保存有界诊断字段，实际模型和用量允许未知。
            call.setModelName(report.modelName());
            call.setProviderCallId(report.providerCallId());
            call.setElapsedMs(report.elapsedMs());
            call.setInputTokens(report.inputTokens());
            call.setOutputTokens(report.outputTokens());
        }
        modelCalls.insert(call);
        return call;
    }

    /**
     * 完成持久状态的原子回写。
     */
    private void advanceState(GovernanceGenerationPO task, String state, String error, int delay) {
        if ("FAILED".equals(state) && task.getModelStartedAt() != null) {
            // 只记录曾实际领取外发许可的失败，不伪造提供方回执。
            recordCall(task, state, error, null);
        }
        records.advance(task.getRequestId(), task.getLeaseToken(), state, error, delay);
    }

    /**
     * 已改变的实际资格或固定依据需要重新生成。
     */
    private BizIllegalException stale() {
        return new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "生成所依据的结构、资料或操作权限已变化");
    }

    /**
     * 生成入参预览与保存共用的确切范围。
     *
     * @param context 固定来源与条目
     * @param paths 实际内容范围
     * @param notes 本次输入的获准修订
     */
    private record Scope(GovernanceEntries.Context context, List<String> paths, List<Note> notes) {}
}
