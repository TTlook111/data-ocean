package com.dataocean.module.governance.service.impl;

import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.mapper.GovernanceCatalogMapper.Column;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceNotes;
import com.dataocean.module.datasource.service.GovernanceNotes.Note;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;
import com.dataocean.module.governance.config.GovernanceAiProperties;
import com.dataocean.module.governance.entity.dto.GenerateScopeDTO;
import com.dataocean.module.governance.entity.dto.GenerateTargetDTO;
import com.dataocean.module.governance.entity.po.GovernanceEvidencePO;
import com.dataocean.module.governance.entity.po.GovernanceGenerationPO;
import com.dataocean.module.governance.entity.po.GovernanceSuggestionPO;
import com.dataocean.module.governance.entity.vo.GenerationAcceptedVO;
import com.dataocean.module.governance.entity.vo.GenerationDetailVO;
import com.dataocean.module.governance.entity.vo.GenerationNoteVO;
import com.dataocean.module.governance.entity.vo.GenerationPreviewVO;
import com.dataocean.module.governance.entity.vo.GenerationSampleVO;
import com.dataocean.module.governance.entity.vo.GenerationSuggestionVO;
import com.dataocean.module.governance.entity.vo.GenerationTargetVO;
import com.dataocean.module.governance.mapper.GovernanceGenerationMapper;
import com.dataocean.module.governance.mapper.GovernanceGenerationRecords;
import com.dataocean.module.governance.mapper.GovernanceSampleMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
     * 表说明可以生成的文本范围。
     */
    private static final List<String> TABLE_PATHS = List.of(
            "businessName",
            "purpose",
            "rowGrain",
            "rowIdentity",
            "dataScope",
            "timeSemantics",
            "updatePolicy",
            "usageNotes",
            "qualityNotes");

    /**
     * 字段解释先生成可独立审核的文本说明，编码映射保留人工核对。
     */
    private static final List<String> COLUMN_PATHS = List.of(
            "businessName",
            "meaning",
            "valueDescription",
            "unit",
            "format",
            "nullMeaning",
            "emptyMeaning",
            "zeroMeaning",
            "timeSemantics",
            "businessTimeZone",
            "usageRule",
            "usageNotes",
            "sensitivityNotes");

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
    private static final Set<String> FAILURES = Set.of(
            "STALE_INPUT",
            "CONTEXT_UNAVAILABLE",
            "MODEL_UNAVAILABLE",
            "MODEL_AUTH",
            "MODEL_RATE_LIMIT",
            "MODEL_FORMAT",
            "MODEL_TIMEOUT",
            "MODEL_CALL_LIMIT",
            "MODEL_RESULT_UNKNOWN");

    /**
     * 部署侧模型开关和获准传输的数据源。
     */
    private final GovernanceAiProperties properties;

    /**
     * 存储持久任务和并发派发状态。
     */
    private final GovernanceGenerationMapper tasks;

    /**
     * 保存完整固定输入与目标范围。
     */
    private final GovernanceGenerationRecords records;

    /**
     * 候选发现的范围校验与版本存储。
     */
    private final GovernanceDiscovery discovery;

    /**
     * 按业务节点固定配置修订并受控取用模型凭据。
     */
    private final ModelRegistry models;

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
     * 模型结果的验证、持久化及受控建议读取。
     */
    private final GovernanceGenerationResults results;

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
     * 样本外发同时受当前业务源访问和治理权限限制。
     */
    private final AccessScopeService access;

    /**
     * 样本事实决定后续阶段是否仍需要同一来源访问权限。
     */
    private final GovernanceSampleMapper sampleAttempts;

    /**
     * 预览真实模型可自动取用的业务资料。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GenerationPreviewVO preview(long sourceId, long object, GenerateScopeDTO input) {
        permissions.require("governance.suggest");
        var source = catalog.source(sourceId, false);
        var scopes = scopes(source, object, input);
        var context = scopes.get(0).context();
        var review = reviews.preview(
                "governance.suggest",
                context.target().commandTarget(),
                context.item() == null ? null : context.item().getVersion(),
                signature(scopes),
                List.of(),
                List.of(),
                List.of());
        return new GenerationPreviewVO(
                Long.toString(sourceId),
                Long.toString(object),
                permissions.has("business-notes.view")
                        ? scopes.get(0).notes().stream()
                                .map(note -> noteView(note, "REGISTERED"))
                                .toList()
                        : null,
                permissions.has("business-notes.view") ? "VISIBLE" : "RESTRICTED",
                review.getReviewToken());
    }

    /**
     * 接受任务时固定输入、条目版本、审计及可靠派发。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GenerationAcceptedVO accept(long sourceId, long object, GenerateScopeDTO input, String command) {
        var target = input.getTargets().isEmpty()
                ? GovernanceTarget.table(sourceId, object)
                : requestedTarget(sourceId, object, input.getTargets().get(0));
        MutationVO saved = commands.execute(
                "governance.suggest",
                command,
                target.commandTarget(),
                input,
                null,
                false,
                false,
                () -> acceptLocked(sourceId, object, input, command));
        var task = require(saved.getTargetId(), sourceId);
        var members = members(task);
        return new GenerationAcceptedVO(
                task.getRequestId(),
                task.getCommandId(),
                combinedState(members),
                members.stream().mapToInt(GovernanceGenerationPO::getVersion).sum());
    }

    /**
     * 在原队列和幂等命令边界内接收一个发现任务。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GenerationAcceptedVO discover(long sourceId, long object, String kind, String command) {
        discovery.node(kind);
        var target = GovernanceTarget.table(sourceId, object);
        MutationVO saved = commands.execute(
                "governance.suggest", command, target.commandTarget() + "/" + kind, kind, null, false, false, () -> {
                    // 接收时锁定同一来源的目录与有权读取的资料。
                    requireModelSource(sourceId);
                    var scope = catalog.source(sourceId, true);
                    var context = entries.get(scope, target);
                    if (!"AVAILABLE".equals(context.availability())) {
                        throw BizIllegalException.state("当前对象不可发现候选");
                    }
                    var inputs = availableNotes(sourceId);
                    long model = models.requireBinding(discovery.node(kind));
                    return saveTask(new Scope(context, List.of(), inputs, kind, model), command, null, 0);
                });
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
        requireReader(task, UserContext.getUser(), permissions.effective(UserContext.getUser()));
        boolean showNotes = permissions.has("business-notes.view");
        boolean diagnostics = permissions.has("diagnostics.view");
        var members = members(task);
        var targets = new ArrayList<GenerationTargetVO>(members.size());
        var supplied = new LinkedHashMap<Long, GovernanceGenerationRecords.Input>();
        for (var member : members) {
            // 单项检查实际资料披露资格，避免一个受限结果隐藏整批其他建议。
            var target = records.target(member.getRequestId());
            var input = records.inputs(member.getRequestId());
            var rounds = sampleAttempts.attempts(member.getRequestId());
            boolean viewSamples = !rounds.isEmpty() && canSample(UserContext.getUser(), source);
            GenerationSuggestionVO suggestion = null;
            if ((showNotes || input.stream().noneMatch(row -> "USED".equals(row.state())))
                    && (viewSamples || rounds.stream().noneMatch(round -> round.returnedRows() > 0))) {
                suggestion = results.view(member.getRequestId(), diagnostics);
            }
            targets.add(new GenerationTargetVO(
                    savedTarget(member, target).view(),
                    member.getRequestId(),
                    member.getDiscoveryKind(),
                    member.getState(),
                    entries.basis(member.getSnapshotId(), target.objectRevisionId(), target.columnRevisionId()),
                    suggestion,
                    member.getFailureCode(),
                    viewSamples
                            ? rounds.stream()
                                    .map(round -> new GenerationSampleVO(
                                            round.attempt(),
                                            round.reason(),
                                            round.status(),
                                            round.returnedRows(),
                                            diagnostics ? round.statementSql() : null))
                                    .toList()
                            : null));
            if (showNotes) {
                // 相同固定资料只列一次，任一项实际用过即标明已取用。
                input.forEach(row -> supplied.merge(
                        row.noteId(), row, (before, after) -> "USED".equals(after.state()) ? after : before));
            }
        }
        String state = combinedState(members);
        return new GenerationDetailVO(
                task.getRequestId(),
                task.getCommandId(),
                Long.toString(source),
                Long.toString(task.getObjectId()),
                Long.toString(task.getRequestedBy()),
                state,
                members.stream().mapToInt(GovernanceGenerationPO::getVersion).sum(),
                Times.instant(members.stream()
                        .map(GovernanceGenerationPO::getStartedAt)
                        .filter(Objects::nonNull)
                        .min(LocalDateTime::compareTo)
                        .orElse(null)),
                Times.instant(
                        members.stream().allMatch(member -> terminal(member.getState()))
                                ? members.stream()
                                        .map(GovernanceGenerationPO::getEndedAt)
                                        .filter(Objects::nonNull)
                                        .max(LocalDateTime::compareTo)
                                        .orElse(null)
                                : null),
                members.size() == 1 ? task.getFailureCode() : null,
                targets,
                showNotes
                        ? supplied.values().stream()
                                .map(row -> noteView(
                                        notes.revision(source, row.noteId(), row.noteRevisionId()), row.state()))
                                .toList()
                        : null);
    }

    /**
     * 流式通知只读取真实状态版本，建议和原始资料由标准详情接口裁剪。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StreamState streamState(long source, String requestId, long actor, Set<String> rights) {
        var task = require(requestId, source);
        requireReader(task, actor, rights);
        var members = members(task);
        return new StreamState(
                combinedState(members),
                members.stream().mapToInt(GovernanceGenerationPO::getVersion).sum());
    }

    /**
     * 详情与持续订阅共享任务发起人与编辑审核资格判断。
     */
    private void requireReader(GovernanceGenerationPO task, long actor, Set<String> rights) {
        boolean owner = task.getRequestedBy() == actor && rights.contains("governance.suggest");
        if (!rights.contains("governance.view")
                || !(owner || rights.contains("governance.edit") || rights.contains("governance.confirm"))) {
            // 无权读取任务时不披露其是否存在。
            throw BizIllegalException.missing();
        }
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
        var source = catalog.source(task.getSourceId(), false);
        var current = eligible(task, source);
        var inputs = inputs(task);
        var attachments = new ArrayList<GovernanceGenerationWire.Note>();
        var inputLimits = new ArrayList<String>();
        int remaining = 4 * MAX_ATTACHMENT_BYTES;
        for (var note : inputs) {
            // 仅选择受控预算内的原件，较大原件保留在来源记录供人工阅读。
            var files = new ArrayList<GovernanceGenerationWire.Attachment>();
            for (var file : note.files()) {
                if (file.byteSize() <= MAX_ATTACHMENT_BYTES && file.byteSize() <= remaining) {
                    // 原件按当前已核验的确切历史修订读取。
                    remaining -= file.byteSize();
                    files.add(new GovernanceGenerationWire.Attachment(
                            Long.toString(file.id()),
                            file.name(),
                            Base64.getEncoder().encodeToString(notes.attachment(note, file.id()))));
                } else if (!inputLimits.contains("INPUT_BUDGET")) {
                    // 仅回传触发预算的原因，原件仍按来源权限回看。
                    inputLimits.add("INPUT_BUDGET");
                }
            }
            attachments.add(new GovernanceGenerationWire.Note(Long.toString(note.noteId()), note.body(), files));
        }
        var origin = current.entry().observation().structure().object();
        if (current.entry().columns().size() > 100) {
            // Java 的结构选择上限也进入当次模型上下文回执。
            inputLimits.add("COLUMN_LIMIT");
        }
        var field = current.field();
        var targetColumn = field == null
                ? null
                : new GovernanceGenerationWire.Column(field.name(), field.nativeType(), field.sourceComment());
        var discoveryScope = task.getDiscoveryKind() == null
                ? null
                : discovery
                        .input(source, current.entry(), task.getDiscoveryKind())
                        .scope();
        return new GovernanceGenerationWire.Context(
                (Objects.toString(origin.schema(), "").isBlank() ? "" : origin.schema() + ".") + origin.name(),
                origin.comment(),
                current.entry().columns().stream()
                        .limit(100)
                        .map(column -> new GovernanceGenerationWire.Column(
                                column.name(), column.nativeType(), column.sourceComment()))
                        .toList(),
                json.strings(records.target(task.getRequestId()).contentPaths()),
                attachments,
                inputLimits,
                targetColumn,
                discoveryScope,
                task.getSampleRowLimits() != null
                        && current.field() != null
                        && properties.allowsSample(
                                task.getSourceId(), origin, current.field().name())
                        && canSample(task.getRequestedBy(), task.getSourceId()));
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
     * 字段解释每轮外发前复核原任务、来源、当前资格和原模型修订。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void verifyContinuation(String requestId, int runVersion) {
        var task = require(requestId, null);
        var target = records.target(task.getRequestId());
        if (!"COLUMN".equals(target.kind())
                || task.getRunVersion() != runVersion
                || task.getModelStartedAt() == null
                || task.getModelRevisionId() == null
                || terminal(task.getState())
                || secondsRemaining(task) <= 0) {
            // 只有当次已取得外发凭据的字段任务才可继续核验。
            throw stale();
        }
        eligible(task, catalog.source(task.getSourceId(), false));
        inputs(task);
        models.verifyActive(task.getModelRevisionId());
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
        if (current == null
                || !Objects.equals(current.getLeaseToken(), claimed.getLeaseToken())
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
                results.finish(current, source, entry, inputs(current), status.result());
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
            advanceState(
                    current,
                    "FAILED",
                    current.getModelStartedAt() == null ? "MODEL_TIMEOUT" : "MODEL_RESULT_UNKNOWN",
                    0);
        } else {
            advanceState(current, state, "FAILED".equals(state) ? failure(status.failureCode()) : null, 1);
        }
    }

    /**
     * 获取建议真正对应的历史结果，供显式写入时校验。
     */
    public GovernanceSuggestionPO adopt(long suggestionId, GovernanceEntries.Context current) {
        var suggestion = results.suggestion(suggestionId);
        if (suggestion == null
                || current.item() == null
                || !suggestion.getItemId().equals(current.item().getId())) {
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
     * 取已通过采用资格检查的建议依据，供正文保存时按内容项关联。
     */
    public List<GovernanceEvidencePO> suggestionEvidence(long id) {
        return results.suggestionEvidence(id);
    }

    /**
     * 复用共同的快照与输入验证，保证预览和正式命令一致。
     */
    private MutationVO acceptLocked(long sourceId, long object, GenerateScopeDTO input, String command) {
        var source = catalog.source(sourceId, true);
        var scopes = scopes(source, object, input);
        var context = scopes.get(0).context();
        reviews.verify(
                "governance.suggest",
                context.target().commandTarget(),
                context.item() == null ? null : context.item().getVersion(),
                signature(scopes),
                input.getReviewToken());
        var root = saveTask(scopes.get(0), command, null, 0);
        for (int position = 1; position < scopes.size(); position++) {
            // 独立执行标识复用原队列防重，所有成员随用户命令原子接收。
            saveTask(scopes.get(position), UUID.randomUUID().toString(), root.getTargetId(), position);
        }
        return root;
    }

    /**
     * 新接收的任务共用资料固定、命令记录及队列状态。
     */
    private MutationVO saveTask(Scope scope, String command, String root, int position) {
        var context = scope.context();
        var sourceId = context.target().source();
        var object = context.target().object();
        var kind = scope.discoveryKind();
        var item = entries.ensure(context.target(), context.entry().generation());

        var task = new GovernanceGenerationPO();
        task.setRequestId(UUID.randomUUID().toString());
        task.setBatchRequestId(root);
        task.setBatchPosition(position);
        task.setCommandId(UUID.fromString(command).toString());
        task.setSourceId(sourceId);
        task.setObjectId(object);
        task.setSnapshotId(context.entry().observation().snapshotId());
        task.setDiscoveryKind(kind);
        if (kind == null
                && context.field() != null
                && properties.allowsSample(
                        sourceId,
                        context.entry().observation().structure().object(),
                        context.field().name())) {
            // 接收时固定有效预算，配置热更新不能追加旧任务额度。
            var limits = properties.sampleLimits(sourceId);
            if (!limits.isEmpty()) {
                task.setSampleRowLimits(json.write(limits));
            }
        }
        task.setModelRevisionId(scope.modelRevision());
        task.setRequestedBy(UserContext.getUser());
        task.setAuthenticationVersion(UserContext.current().authenticationVersion());
        task.setState("PENDING_DISPATCH");
        task.setRunVersion(1);
        task.setVersion(1);
        tasks.insert(task);
        records.insertTarget(
                task.getRequestId(),
                item.getId(),
                item.getVersion(),
                context.entry().observation().revisionId(),
                context.columnRevision(),
                json.write(scope.paths()));
        for (var note : scope.notes()) {
            // 同一来源锁中登记准确输入，原件删除与取用只能有一方成功。
            notes.reference(sourceId, note, "GOV_TASK_INPUT", task.getRequestId());
            records.addInput(task.getRequestId(), note.noteId(), note.revisionId());
        }
        if (kind == null) {
            // 关联、指标发现不计入表说明及字段的进度。
            impacts.started(sourceId, item.getId());
        }
        events.requested(sourceId, task.getRequestId(), task.getCommandId());
        if (root == null) {
            // 一笔用户命令留一条接收审计，单项回执仍按实际执行编号追溯。
            audit.record(AuditWriteDTO.builder()
                    .actionCode("governance.suggest")
                    .targetType("governance_generation")
                    .targetId(task.getRequestId())
                    .targetName(
                            context.entry().observation().structure().object().name())
                    .changes(List.of(new ChangeVO("state", "实际生成状态", null, "等待派发")))
                    .securityVersion(commands.auditSecurityVersion(false))
                    .commandId(task.getCommandId())
                    .build());
        }
        return new MutationVO(command, "governance_generation", task.getRequestId(), 1);
    }

    /**
     * 权限、源、结构及人工内容原版本需持续满足。
     */
    private GovernanceEntries.Context eligible(GovernanceGenerationPO task, GovernanceCatalog.Source source) {
        requireModelSource(source.id());
        if (task.getDiscoveryKind() != null && !Objects.equals(task.getSnapshotId(), source.snapshotId())) {
            // 多表发现只允许在接收时固定的整个成功目录上执行。
            throw stale();
        }
        var rights = actors.require(
                task.getRequestedBy(), task.getAuthenticationVersion(), "governance.view", "governance.suggest");
        if (!records.inputs(task.getRequestId()).isEmpty() && !rights.contains("business-notes.view")) {
            // 发起人失去原资料资格时停止新的外发和结果应用。
            throw BizIllegalException.denied();
        }
        var destination = records.target(task.getRequestId());
        var current = entries.get(source, savedTarget(task, destination));
        if (!"AVAILABLE".equals(current.availability())
                || current.item() == null
                || current.item().getVersion() != destination.baseItemVersion()
                || current.entry().observation().revisionId() != destination.objectRevisionId()
                || !Objects.equals(current.columnRevision(), destination.columnRevisionId())) {
            throw stale();
        }
        if (sampleAttempts.used(task.getRequestId())
                && (current.field() == null
                        || !canSample(task.getRequestedBy(), source.id())
                        || !properties.allowsSample(
                                source.id(),
                                current.entry().observation().structure().object(),
                                current.field().name()))) {
            // 已读取数据的任务在模型续轮、建议落库和回填时仍须具备授权。
            throw BizIllegalException.denied();
        }
        return current;
    }

    /**
     * 读取样本前持久化额度时绑定同一来源、当前代次和目标字段。
     */
    SampleContext sampleContext(String requestId, int runVersion) {
        var initial = require(requestId, null);
        var source = catalog.source(initial.getSourceId(), true);
        var task = records.lock(initial.getRequestId());
        if (task.getRunVersion() != runVersion
                || task.getModelStartedAt() == null
                || terminal(task.getState())
                || secondsRemaining(task) < 5) {
            // 只在已领取模型配置且仍有充足时长时允许访问业务源。
            throw stale();
        }
        var context = eligible(task, source);
        if (task.getSampleRowLimits() == null
                || context.field() == null
                || !properties.allowsSampleRules(
                        source.id(),
                        access.requireSource(task.getRequestedBy(), source.id()).getRules())) {
            // 缺少业务源授权时仍可用结构及资料。
            throw BizIllegalException.denied();
        }
        inputs(task);
        models.verifyActive(task.getModelRevisionId());
        return new SampleContext(
                source,
                context.field(),
                context.entry().observation().structure().object(),
                List.of(json.read(task.getSampleRowLimits(), Integer[].class)));
    }

    /**
     * 业务源授权或当前输出限制变化后停止自动处理样本。
     */
    private boolean canSample(long user, long source) {
        // 可选授权查询不抛出业务拒绝，当前生成事务仍能使用目录及资料。
        return access.findSource(user, source)
                .filter(scope -> properties.allowsSampleRules(source, scope.getRules()))
                .isPresent();
    }

    /**
     * 凭据、模型目的地和可传数据源完全由服务端受控配置。
     */
    private void requireModelSource(long source) {
        if (!properties.isEnabled() || !properties.getModelSourceIds().contains(source)) {
            // 当前源未授权对指定模型发送目录和企业说明。
            throw BizIllegalException.state("当前数据源未配置治理模型传输范围");
        }
    }

    /**
     * 从原任务固定的输入检查资料版本实际是否改变。
     */
    private List<Note> inputs(GovernanceGenerationPO task) {
        return records.inputs(task.getRequestId()).stream()
                .map(input -> {
                    // 原文标题变化可以等价，正文和附件实质变化使原任务失效。
                    var prior = notes.revision(task.getSourceId(), input.noteId(), input.noteRevisionId());
                    if (!notes.current(task.getSourceId(), input.noteId()).equivalent(prior)) {
                        throw stale();
                    }
                    return prior;
                })
                .toList();
    }

    /**
     * 固定本批所有目标和同一时刻选定的有效资料。
     */
    private List<Scope> scopes(GovernanceCatalog.Source source, long object, GenerateScopeDTO input) {
        requireModelSource(source.id());
        var inputs = availableNotes(source.id());
        var scopes = new ArrayList<Scope>();
        for (var target : input.getTargets()) {
            // 精确校验每项当前可办性，留存各自作用范围和模型配置修订。
            scopes.add(scope(source, object, inputs, target));
        }
        for (var kind : input.getDiscoveryKinds()) {
            // 发现以所选表及成功目录为锚点，各自固定节点配置。
            var context = entries.get(source, GovernanceTarget.table(source.id(), object));
            if (!"AVAILABLE".equals(context.availability())) {
                throw BizIllegalException.state("当前对象不可发现候选");
            }
            scopes.add(new Scope(context, List.of(), inputs, kind, models.requireBinding(discovery.node(kind))));
        }
        if (scopes.isEmpty()
                || scopes.stream()
                                .map(scope -> List.of(
                                        scope.context().target().commandTarget(),
                                        Objects.toString(scope.discoveryKind(), "")))
                                .distinct()
                                .count()
                        != scopes.size()) {
            // 一个生成命令至少处理一项，且同一目标只能出现一次。
            throw BizIllegalException.invalid("请选择不重复的生成条目");
        }
        return scopes;
    }

    /**
     * 核验单项表说明或字段本次可以生成的业务字段。
     */
    private Scope scope(GovernanceCatalog.Source source, long object, List<Note> inputs, GenerateTargetDTO chosen) {
        var target = requestedTarget(source.id(), object, chosen);
        var context = entries.get(source, target);
        entries.verify(context, chosen.getPrecondition(), false);
        var availablePaths = target.isColumn() ? COLUMN_PATHS : TABLE_PATHS;
        var paths = chosen.getContentPaths() == null ? availablePaths : chosen.getContentPaths();
        if (paths.isEmpty()
                || paths.stream().distinct().count() != paths.size()
                || !availablePaths.containsAll(paths)) {
            // 字段和表说明各自限定可生成文本。
            throw BizIllegalException.invalid("请选择当前条目可生成的内容");
        }
        return new Scope(context, paths, inputs, null, models.requireBinding(node(target)));
    }

    /**
     * 复用有界的同源有效资料选择。
     */
    private List<Note> availableNotes(long source) {
        return permissions.has("business-notes.view")
                ? notes.active(source).stream().limit(NOTE_LIMIT).toList()
                : List.of();
    }

    /**
     * 核对确切结构及实质依据，标题改动仍使用同内容固定修订。
     */
    private List<Object> signature(List<Scope> scopes) {
        return List.of(
                scopes.stream()
                        .map(scope -> List.of(
                                scope.context().target().commandTarget(),
                                scope.context().entry().observation().revisionId(),
                                scope.context().structureRevision(),
                                scope.context().item() == null
                                        ? 0
                                        : scope.context().item().getVersion(),
                                Objects.toString(scope.discoveryKind(), ""),
                                scope.modelRevision(),
                                scope.paths()))
                        .toList(),
                scopes.get(0).notes().stream()
                        .map(note -> List.of(
                                note.noteId(),
                                note.body(),
                                note.files().stream()
                                        .map(GovernanceNotes.File::digest)
                                        .sorted()
                                        .toList()))
                        .toList());
    }

    /**
     * 绑定客户端提供的条目标识，后续由目录服务核对成员身份。
     */
    private GovernanceTarget requestedTarget(long source, long object, GenerateTargetDTO chosen) {
        return GovernanceTarget.parse(source, object, chosen.getKind(), chosen.getTargetId());
    }

    /**
     * 根编号回读整批，已知执行编号仍可用于读取独立结果。
     */
    private List<GovernanceGenerationPO> members(GovernanceGenerationPO task) {
        if (task.getBatchRequestId() != null) {
            return List.of(task);
        }
        var members = new ArrayList<GovernanceGenerationPO>();
        members.add(task);
        members.addAll(records.batchMembers(task.getRequestId()));
        return members;
    }

    /**
     * 只有每项进入终态才结束批次，并单独表达部分失败。
     */
    private String combinedState(List<GovernanceGenerationPO> members) {
        var states = members.stream()
                .map(GovernanceGenerationPO::getState)
                .distinct()
                .toList();
        if (states.size() == 1) {
            return states.get(0);
        }
        if (states.stream().allMatch(this::terminal)) {
            return "PARTIAL_FAILED";
        }
        return states.stream().anyMatch(state -> terminal(state) || "RUNNING".equals(state)) ? "RUNNING" : "QUEUED";
    }

    /**
     * 由任务和固定条目记录重建实际治理目标。
     */
    private GovernanceTarget savedTarget(GovernanceGenerationPO task, GovernanceGenerationRecords.Target target) {
        return new GovernanceTarget(task.getSourceId(), task.getObjectId(), target.kind(), target.targetId());
    }

    /**
     * 选择当前真实业务目标所分配的模型。
     */
    private String node(GovernanceTarget target) {
        return target.isColumn() ? ModelRegistry.FIELD_EXPLANATION : ModelRegistry.TABLE_DESCRIPTION;
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
        return Duration.between(
                        LocalDateTime.now(ZoneOffset.UTC), task.getCreatedAt().plusSeconds(TASK_SECONDS))
                .toSeconds();
    }

    /**
     * 完成持久状态的原子回写。
     */
    private void advanceState(GovernanceGenerationPO task, String state, String error, int delay) {
        if ("FAILED".equals(state) && task.getModelStartedAt() != null) {
            // 只记录曾实际领取外发许可的失败，不伪造提供方回执。
            results.recordFailure(task, error);
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
     * @param discoveryKind 关联、指标发现类型，普通生成为空
     * @param modelRevision 当次选择的模型配置修订
     */
    private record Scope(
            GovernanceEntries.Context context,
            List<String> paths,
            List<Note> notes,
            String discoveryKind,
            long modelRevision) {}

    /**
     * @param source 当前用于实际读取的已核验源配置
     * @param field 任务绑定的实际字段
     * @param object 已采集到的表和限定名
     * @param rowLimits 接收时固定的每轮行数
     */
    record SampleContext(GovernanceCatalog.Source source, Column field, ObjectRef object, List<Integer> rowLimits) {}
}
