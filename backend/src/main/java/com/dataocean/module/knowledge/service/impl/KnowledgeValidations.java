package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dataocean.common.Ids;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.governance.config.GovernanceAiProperties;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ServiceActorPermission;
import com.dataocean.module.knowledge.entity.dto.KnowledgeValidationDTO.Judgment;
import com.dataocean.module.knowledge.entity.dto.KnowledgeValidationDTO.Start;
import com.dataocean.module.knowledge.entity.po.KnowledgeReleaseCandidatePO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeTestVO.Content;
import com.dataocean.module.knowledge.entity.vo.KnowledgeValidationVO;
import com.dataocean.module.knowledge.mapper.KnowledgeBuildMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeBuildMapper.Build;
import com.dataocean.module.knowledge.mapper.KnowledgeBuildMapper.Chunk;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;
import com.dataocean.module.knowledge.mapper.KnowledgeReleaseTaskMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeReleaseTaskMapper.Task;
import com.dataocean.module.knowledge.mapper.KnowledgeValidationMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeValidationMapper.Question;
import com.dataocean.module.knowledge.mapper.KnowledgeValidationMapper.Run;
import com.dataocean.module.knowledge.mapper.KnowledgeVersionPauseMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeVersionPauseMapper.Pause;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Evidence;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Fragment;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Verified;
import com.dataocean.module.knowledge.service.KnowledgeIndexProperties;
import com.dataocean.module.knowledge.service.KnowledgeReleasePreflight;
import com.dataocean.module.knowledge.service.KnowledgeValidationQuery;
import com.dataocean.module.knowledge.service.KnowledgeValidationService;
import com.dataocean.module.knowledge.service.KnowledgeValidationWire.Input;
import com.dataocean.module.knowledge.service.KnowledgeValidationWire.Receipt;
import com.dataocean.module.knowledge.service.KnowledgeValidationWire.Status;
import com.dataocean.module.model.service.ModelRegistry;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 保证逐题执行、裁定和发布任务状态都属于同一固定候选及运行。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeValidations implements KnowledgeValidationService {

    /**
     * 同一轮覆盖排队、召回及只读查询的预算。
     */
    private static final int SECONDS = 180;

    /**
     * 支持首次运行及对已知终态重新运行。
     */
    private static final Set<String> STARTABLE = Set.of(
            "READY_FOR_VALIDATION", "AWAITING_JUDGMENT", "VALIDATION_FAILED", "VALIDATION_ERROR", "READY_TO_PUBLISH");

    /**
     * 从内部依赖只接纳已知且可披露的稳定错误。
     */
    private static final Set<String> FAILURE_CODES = Set.of(
            "INPUT_STALE",
            "MODEL_AUTH",
            "MODEL_FORMAT",
            "MODEL_RATE_LIMIT",
            "MODEL_RESULT_UNKNOWN",
            "INDEX_UNAVAILABLE",
            "INDEX_READBACK_MISMATCH",
            "BUILD_INTERRUPTED",
            "BUILD_TIMEOUT",
            "VALIDATION_TIMEOUT");

    /**
     * 运行、技术结果和独立裁定。
     */
    private final KnowledgeValidationMapper runs;

    /**
     * 当前使用控制及运行归属。
     */
    private final KnowledgeVersionPauseMapper pauses;

    /**
     * 生产限制与正常依赖分别核对。
     */
    private final KnowledgeVersionUse versionUse;

    /**
     * 任务的来源、当前阶段和聚合版本。
     */
    private final KnowledgeReleaseTaskMapper tasks;

    /**
     * 读取候选的确切构建及不可变片段。
     */
    private final KnowledgeBuildMapper builds;

    /**
     * 读取固定正文引用并核对治理状态。
     */
    private final KnowledgeDocumentMapper documents;

    /**
     * 与其他发布写操作使用同一来源锁。
     */
    private final GovernanceCatalog catalog;

    /**
     * 构建和验证使用同一套固定输入复核规则。
     */
    private final KnowledgeReleasePreflight preflight;

    /**
     * 实际计数走采集模块提供的只读连接。
     */
    private final KnowledgeValidationQuery query;

    /**
     * 仅返回固定模型修订的短期调用资料。
     */
    private final ModelRegistry models;

    /**
     * 传输只能发生在部署明示批准的来源。
     */
    private final KnowledgeIndexProperties index;

    /**
     * 使用现有内部 AI 服务开关。
     */
    private final GovernanceAiProperties transport;

    /**
     * 按每个既有审核人的当前授权核对执行与披露。
     */
    private final AccessScopeService access;

    /**
     * 固定审核人本次运行所用的认证版本。
     */
    private final UserMapper users;

    /**
     * 工作者不能冒用高于原执行人的权限。
     */
    private final ServiceActorPermission actors;

    /**
     * 页面功能和动作权限继续由 IAM 判定。
     */
    private final PermissionService permissions;

    /**
     * 运行和裁定复用命令防重及审计。
     */
    private final MutationService commands;

    /**
     * 记录显式运行及判断动作。
     */
    private final AuditService audit;

    /**
     * 读取固定题意与证据，比较来源范围摘要。
     */
    private final JsonCodec json;

    /**
     * 生成当前业务来源的稳定授权指纹。
     */
    private final Fingerprints fingerprints;

    /**
     * 随已核验的构建回执一起提交，重启后仍可调度。
     */
    public void afterBuild(Source source, Build build) {
        // 通过构建操作取得可信发起人，逐题仍用审核时绑定的执行身份。
        enqueue(
                source,
                build,
                "READY_FOR_VALIDATION",
                build.requestedBy(),
                build.authenticationVersion(),
                "releases.build");
    }

    /**
     * 重新执行固定候选的全部审核题，保留之前运行。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO start(long sourceId, long id, String command, Start input) {
        return commands.execute(
                "releases.test",
                command,
                "knowledge-validation:" + sourceId + "/" + id,
                input,
                input.getExpectedVersion(),
                false,
                false,
                () -> {
                    // 先锁来源并核对页面资格与确切代次。
                    permissions.require("releases.view");
                    var source = catalog.source(sourceId, true);
                    var task = task(sourceId, id);
                    version(task, input.getExpectedVersion());
                    if (!STARTABLE.contains(task.status())
                            || !Objects.equals(task.currentBuildId(), input.getBuildId())) {
                        throw BizIllegalException.state("当前构建代次不可运行验证");
                    }
                    var build = builds.get(input.getBuildId());
                    verifyBasis(source, build, task.candidateId());
                    String run = enqueue(
                            source,
                            build,
                            task.status(),
                            UserContext.getUser(),
                            UserContext.current().authenticationVersion(),
                            "releases.test");
                    var saved = task(sourceId, id);
                    audit(command, "releases.test", "knowledge_validation", run, task.title(), null);
                    return new MutationVO(command, "knowledge_validation", run, saved.version());
                });
    }

    /**
     * 恢复动作不能用于已解除或被新暂停取代的记录。
     */
    Pause requirePause(Binding binding, long expected) {
        var pause = pauses.active(binding.scopeId());
        if (pause == null || pause.id() != expected) {
            throw BizIllegalException.state("当前暂停已变化，请重新核对");
        }
        return pause;
    }

    /**
     * 为当前候选生成独立的耐久运行，不修改任何旧证据。
     */
    private String enqueue(Source source, Build build, String previous, long actor, long auth, String operation) {
        String id = UUID.randomUUID().toString();
        runs.create(id, build.candidateId(), build.id(), actor, auth, operation, source.configurationId(), null, null);
        if (runs.activate(build.candidateId(), build.id(), previous, id) != 1) {
            // 旧来源变更或重复工作回执不能覆盖新运行。
            throw BizIllegalException.conflict();
        }
        addQuestions(source, id, builds.testRevisions(build.candidateId()));
        return id;
    }

    /**
     * 普通候选与隔离恢复共用审核题快照和逐题执行身份。
     */
    void addQuestions(Source source, String id, List<Long> tests) {
        for (long revision : tests) {
            // 允许在详情按题显示实际授权问题，权限不足的题不进入召回。
            long executor = runs.executor(revision);
            Long authentication = null;
            String grant = null;
            try {
                var user = users.selectById(executor);
                if (user == null) {
                    throw BizIllegalException.denied();
                }
                grant = authorization(source.id(), executor, user.getAuthenticationVersion());
                authentication = user.getAuthenticationVersion();
            } catch (BizIllegalException denied) {
                // 当前身份或来源资格失效，留存可重测的条件失败。
            }
            runs.addQuestion(IdWorker.getId(), id, revision, authentication, grant);
        }
        var run = runs.get(id);
        if (runs.questions(id).stream().noneMatch(this::pending)) {
            // 没有可执行题时直接阻断，不发送空工作。
            settle(run);
        }
    }

    /**
     * 从已提交的队列取得短租约。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Run claimDispatch() {
        String id = runs.next();
        if (id == null) {
            // 当前无可派发记录。
            return null;
        }
        runs.reserve(id, UUID.randomUUID().toString());
        return runs.get(id);
    }

    /**
     * 向现有受认证工作者一次性交付确切代次及获准题干。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Input claimInput(String id, int version) {
        var initial = readRun(id);
        var source = catalog.source(initial.sourceId(), true);
        var run = readRun(id);
        var build = active(source, run, version);
        for (var question : runs.questions(id)) {
            // 题单身份变化时止于服务端，不将失权题干外发。
            if (pending(question) && !authorizedQuestion(source, question)) {
                runs.result(question.id(), "CONDITIONS_INVALID", null, null, null, "ACCESS_CHANGED");
            }
        }
        var questions = runs.questions(id).stream()
                .filter(this::pending)
                .map(row -> new com.dataocean.module.knowledge.service.KnowledgeValidationWire.Question(
                        Ids.text(row.id()), content(row).getQuestion()))
                .toList();
        if (questions.isEmpty()) {
            // 应用回执会把没有权限的题收敛为条件失败。
            throw BizIllegalException.denied();
        }
        if (runs.claimInput(id) != 1) {
            // 一次真实外发结果尚不明确时不能再调用。
            throw new BizIllegalException(ErrorCode.EXECUTION_UNCERTAIN, "验证输入已经领取");
        }
        var candidate = builds.candidate(source.id(), run.candidateId());
        int remaining = remaining(run);
        return new Input(
                Ids.text(candidate.getId()),
                Ids.text(candidate.getDocumentRevisionId()),
                candidate.getFingerprint(),
                build.collectionName(),
                builds.chunks(build.id()).stream()
                        .map(chunk -> new Verified(chunk.ordinal(), chunk.digest()))
                        .toList(),
                questions,
                models.invocation(candidate.getEmbeddingRevisionId(), remaining),
                remaining);
    }

    /**
     * 实际调用、召回及交付前检查任务和原题执行资格。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recheck(String id, int version) {
        var initial = readRun(id);
        var source = catalog.source(initial.sourceId(), true);
        active(source, readRun(id), version);
        if (runs.questions(id).stream().anyMatch(row -> pending(row) && !authorizedQuestion(source, row))) {
            // 旧资格不得用于下一批真实外发。
            throw BizIllegalException.denied();
        }
    }

    /**
     * 接收获准的匹配片段，再独立读取真实源计数。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void apply(Run dispatched, Status status) {
        if (remaining(dispatched) > 0
                && (status == null
                        || status.runVersion() != 1
                        || !Set.of("FAILED", "SUCCEEDED").contains(status.state()))) {
            // 未确定的工作只在同一编号再次核实。
            runs.postpone(dispatched.id(), dispatched.dispatchToken());
            return;
        }
        var source = catalog.source(dispatched.sourceId(), true);
        var run = readRun(dispatched.id());
        if (!Objects.equals(run.dispatchToken(), dispatched.dispatchToken()) || !running(run)) {
            // 旧租约和取消后的工作结果均不写入。
            return;
        }

        Build build;
        try {
            build = active(source, run, 1);
        } catch (BizIllegalException stale) {
            invalidate(run, remaining(run) <= 0 ? "VALIDATION_TIMEOUT" : "INPUT_STALE");
            return;
        }

        var chunks = builds.chunks(build.id()).stream().collect(Collectors.toMap(Chunk::ordinal, Function.identity()));
        if (!"SUCCEEDED".equals(status.state())) {
            // 网络、索引及模型失败产生逐题可见的执行异常。
            String code = status.failureCode();
            invalidate(run, code != null && FAILURE_CODES.contains(code) ? code : "BUILD_INTERRUPTED");
            return;
        }
        var questions = runs.questions(run.id());
        var received = match(source, run, build, status.result(), chunks, questions);
        if (received == null) {
            // 只接受同代次、同题集、完整且可以验证的片段回执。
            invalidate(run, "INDEX_READBACK_MISMATCH");
            return;
        }
        for (var question : questions) {
            // 已明确条件失效的题保持原失败记录。
            if (pending(question)) {
                evaluate(source, run, question, received.get(Ids.text(question.id())));
            }
        }
        settle(run);
    }

    /**
     * 核实工作者针对各题返回的实际片段编号和摘要。
     */
    private Map<String, List<Integer>> match(
            Source source,
            Run run,
            Build build,
            Receipt receipt,
            Map<Integer, Chunk> chunks,
            List<Question> questions) {
        var candidate = builds.candidate(source.id(), run.candidateId());
        if (receipt == null
                || receipt.matches() == null
                || !build.collectionName().equals(receipt.collection())
                || !candidate.getFingerprint().equals(receipt.fingerprint())) {
            return null;
        }
        var matches = new HashMap<String, List<Integer>>();
        for (var hit : receipt.matches()) {
            // 一道题只接收一份回执，缺失片段与重复片段是不同错误。
            if (hit == null || hit.id() == null || hit.fragments() == null || matches.containsKey(hit.id())) {
                return null;
            }
            var ordinals = new HashSet<Integer>();
            for (var fragment : hit.fragments()) {
                var chunk = fragment == null ? null : chunks.get(fragment.ordinal());
                if (chunk == null || !chunk.digest().equals(fragment.digest()) || !ordinals.add(fragment.ordinal())) {
                    return null;
                }
            }
            matches.put(
                    hit.id(), hit.fragments().stream().map(Verified::ordinal).toList());
        }
        var expected = questions.stream()
                .filter(this::pending)
                .map(row -> Ids.text(row.id()))
                .collect(Collectors.toSet());
        return expected.equals(matches.keySet()) ? matches : null;
    }

    /**
     * 说明题固定实际召回正文，数值题在同版证据和原执行身份下真实查询。
     */
    private void evaluate(Source source, Run run, Question row, List<Integer> hits) {
        if (remaining(run) <= 0 || !authorizedQuestion(source, row)) {
            // 不在失效条件下继续发起源查询。
            runs.result(
                    row.id(),
                    "CONDITIONS_INVALID",
                    null,
                    null,
                    null,
                    remaining(run) <= 0 ? "VALIDATION_TIMEOUT" : "ACCESS_CHANGED");
            return;
        }
        if (hits.isEmpty()) {
            // 空召回没有可证实答案，不能裁定或自动通过。
            runs.result(row.id(), "EXECUTION_ERROR", null, null, null, "NO_EVIDENCE");
            return;
        }
        var content = content(row);
        if ("EXPLANATION".equals(content.getMode())) {
            // 实际答案是本题召回的原文，审核预期完全独立。
            runs.result(row.id(), "PENDING_JUDGMENT", null, json.write(hits), null, null);
            return;
        }
        if (!"NUMERIC".equals(content.getMode())
                || content.getCountRule() == null
                || content.getExpectedCount() == null) {
            // 留存旧审核条件缺失的实际原因，不执行歧义题目。
            runs.result(row.id(), "CONDITIONS_INVALID", null, null, null, "QUERY_BASIS_INVALID");
            return;
        }
        try {
            // 仅保留命中章节所支持的已冻结治理条件。
            var facts = documents.references(Ids.parse(content.getDocumentRevisionId())).stream()
                    .filter(ref -> hits.contains(ref.sectionIndex()))
                    .toList();
            var actual = actors.asActor(
                    row.executorId(),
                    row.authenticationVersion(),
                    () -> {
                        try {
                            return query.execute(source, facts, content.getCountRule());
                        } catch (SQLException failed) {
                            // 不记录驱动错误中的源地址、SQL 参数和数据库内容。
                            throw new QueryFailure();
                        }
                    },
                    "test-cases.review",
                    "test-cases.view");
            if (!authorizedQuestion(source, row)) {
                // 读回期间撤权时不得保存或披露实际聚合值。
                throw BizIllegalException.denied();
            }
            runs.result(
                    row.id(),
                    actual.value() == content.getExpectedCount() ? "PASSED" : "FAILED",
                    actual.value(),
                    json.write(hits),
                    actual.statement(),
                    null);
        } catch (QueryFailure failed) {
            log.debug("标准题只读执行失败，runId={}，resultId={}", run.id(), row.id());
            runs.result(row.id(), "EXECUTION_ERROR", null, json.write(hits), null, "QUERY_FAILED");
        } catch (BizIllegalException invalid) {
            runs.result(row.id(), "CONDITIONS_INVALID", null, null, null, "QUERY_BASIS_INVALID");
        }
    }

    /**
     * 标记尚未得到技术结果的题并推进真实阻断状态。
     */
    void invalidate(Run run, String reason) {
        for (var row : runs.questions(run.id())) {
            // 保留已经明确的权限问题和旧实际记录。
            if (pending(row)) {
                runs.result(
                        row.id(),
                        "INPUT_STALE".equals(reason) ? "CONDITIONS_INVALID" : "EXECUTION_ERROR",
                        null,
                        null,
                        null,
                        reason);
            }
        }
        settle(run);
    }

    /**
     * 取消仅影响进行中的当前运行，历史轮次仍可回看。
     */
    public void cancelCurrent(long source, long candidateId) {
        var candidate = builds.candidate(source, candidateId);
        String id = candidate.getCurrentValidationId();
        if (id != null && running(readRun(id))) {
            for (var row : runs.questions(id)) {
                if (pending(row)) {
                    // 已运行的证据不被取消操作改写。
                    runs.result(row.id(), "CANCELED", null, null, null, null);
                }
            }
            runs.settle(id, "CANCELED");
        }
    }

    /**
     * 当前说明题的裁定必须绑定具体运行、题目和这次页面版本。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO judge(long sourceId, long taskId, String runId, long questionId, String command, Judgment input) {
        return commands.execute(
                "test-cases.judge",
                command,
                "knowledge-judgment:" + sourceId + "/" + taskId + "/" + runId + "/" + questionId,
                input,
                input.getExpectedVersion(),
                false,
                false,
                () -> {
                    // 运行重测、修改范围和权限撤销后不能裁定旧窗。
                    requireReader(sourceId);
                    var source = catalog.source(sourceId, true);
                    var task = task(sourceId, taskId);
                    version(task, input.getExpectedVersion());
                    var run = forTask(sourceId, taskId, runId);
                    if (run.recoveryPauseId() == null) {
                        // 普通裁定仍只能办理当前固定候选的当前运行。
                        var candidate = builds.candidate(sourceId, run.candidateId());
                        if (candidate == null
                                || !run.id().equals(candidate.getCurrentValidationId())
                                || !Set.of("AWAITING_JUDGMENT", "VALIDATION_FAILED", "VALIDATION_ERROR")
                                        .contains(task.status())) {
                            throw BizIllegalException.state("仅可裁定当前运行中的待裁定结果");
                        }
                        verifyBasis(source, builds.get(run.buildId()), run.candidateId());
                    } else {
                        // 恢复裁定只改变隔离证据，原发布任务始终不可被重新推进。
                        verifyRecoveryRun(source, run);
                    }
                    if (!Objects.equals(source.configurationId(), run.connectionRevisionId())
                            || runs.questions(run.id()).stream()
                                    .filter(item -> Set.of("PASSED", "FAILED", "PENDING_JUDGMENT")
                                            .contains(item.state()))
                                    .anyMatch(item -> !authorizedQuestion(source, item))) {
                        // 陈旧证据不得参与新候选判断。
                        throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "本轮执行条件已变化，请重新验证");
                    }
                    var row = runs.questions(run.id()).stream()
                            .filter(value -> value.id() == questionId)
                            .findFirst()
                            .orElseThrow(BizIllegalException::missing);
                    if (!"PENDING_JUDGMENT".equals(row.state()) || row.decision() != null) {
                        // 数值失败和执行异常没有人工改判入口。
                        throw BizIllegalException.state("本题没有待办理的裁定");
                    }
                    runs.judge(
                            questionId, input.getDecision(), input.getReason().strip(), UserContext.getUser());
                    settle(run);
                    audit(
                            command,
                            "test-cases.judge",
                            "knowledge_validation_result",
                            Ids.text(questionId),
                            task.title(),
                            input.getReason().strip());
                    return new MutationVO(
                            command,
                            "knowledge_validation_result",
                            Ids.text(questionId),
                            task(sourceId, taskId).version());
                });
    }

    /**
     * 临切换重新核对当前运行及各题可信执行条件，历史或未知结果不能提供发布依据。
     */
    public void requirePublishable(Source source, Build build, KnowledgeReleaseCandidatePO candidate) {
        verifyBasis(source, build, candidate.getId());
        requirePassed(source, build.id(), candidate.getId(), candidate.getCurrentValidationId());
    }

    /**
     * 以直接上一版保存的真实运行重核当前适用关键题及测试身份。
     */
    public void requireRollbackEvidence(Source source, Binding target) {
        var questions = requirePassed(source, target.buildId(), target.candidateId(), target.validationId());
        var original = questions.stream().map(Question::testRevisionId).sorted().toList();
        var current = preflight.requireRelatedTests(source.id(), target.documentId(), target.documentRevisionId());
        if (!original.equals(current)) {
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "直接上一版的相关已审核题单已变化");
        }
    }

    /**
     * 普通发布与直接上一版证据均需原运行可信且各题仍有实际授权。
     */
    private List<Question> requirePassed(Source source, String buildId, long candidateId, String id) {
        var run = id == null ? null : runs.get(id);
        if (run == null
                || !run.buildId().equals(buildId)
                || run.candidateId() != candidateId
                || !"READY_TO_PUBLISH".equals(run.state())) {
            // 当前可用的同代次运行才可作为发布依据。
            throw new BizIllegalException(ErrorCode.KNOWLEDGE_NOT_READY, "本次索引的逐题验证尚未全部通过");
        }
        var questions = runs.questions(id);
        if (!Objects.equals(source.configurationId(), run.connectionRevisionId())
                || questions.isEmpty()
                || questions.stream()
                        .anyMatch(row -> !"PASSED".equals(row.decision() == null ? row.state() : row.decision())
                                || !authorizedQuestion(source, row))) {
            // 测试身份及源配置发生变化时旧成绩不继续有效。
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "验证条件或执行身份已变化，请重新运行验证");
        }
        return questions;
    }

    /**
     * 按技术异常、明确失败和待裁定的实际优先级推进。
     */
    private void settle(Run run) {
        var values = runs.questions(run.id()).stream()
                .map(row -> row.decision() == null ? row.state() : row.decision())
                .toList();
        String stage;
        if (values.isEmpty()
                || values.contains("EXECUTION_ERROR")
                || values.contains("CONDITIONS_INVALID")
                || values.contains("PENDING")
                || values.contains("CANCELED")) {
            stage = "VALIDATION_ERROR";
        } else if (values.contains("FAILED")) {
            stage = "VALIDATION_FAILED";
        } else if (values.contains("PENDING_JUDGMENT")) {
            stage = "AWAITING_JUDGMENT";
        } else {
            stage = run.recoveryPauseId() == null ? "READY_TO_PUBLISH" : "RECOVERY_PASSED";
        }
        runs.settle(run.id(), stage);
        if (run.recoveryPauseId() == null && runs.advance(run.candidateId(), run.id(), stage) != 1) {
            // 当前指针变化时整次交付回滚。
            throw BizIllegalException.conflict();
        }
    }

    /**
     * 提供当前和历史运行，实际数据仅授权可见。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public KnowledgeValidationVO detail(long source, long taskId, String requestedRun) {
        var task = task(source, taskId);
        var history = runs.history(taskId);
        var candidate = task.candidateId() == null ? null : builds.candidate(source, task.candidateId());
        var run = requestedRun == null
                ? (candidate == null || candidate.getCurrentValidationId() == null
                        ? null
                        : readRun(candidate.getCurrentValidationId()))
                : forTask(source, taskId, requestedRun);
        if (run != null && run.recoveryPauseId() != null) {
            // 隔离结果只在同一绑定的恢复入口回看。
            throw BizIllegalException.missing();
        }
        return validationDetail(source, run, history);
    }

    /**
     * 普通发布和隔离恢复的逐题数据遵守同一披露权限。
     */
    KnowledgeValidationVO validationDetail(long source, Run run, List<Run> history) {
        boolean visible = canRead(source);
        boolean evidenceVisible = visible && permissions.has("knowledge.view");
        var questions =
                !visible || run == null ? List.<KnowledgeValidationVO.Question>of() : view(run, evidenceVisible);
        return new KnowledgeValidationVO(
                run == null ? null : viewRun(run),
                history.stream().map(this::viewRun).toList(),
                visible,
                evidenceVisible,
                questions);
    }

    /**
     * 按原代次的持久证据构造题目回读。
     */
    private List<KnowledgeValidationVO.Question> view(Run run, boolean evidenceVisible) {
        // 只有具备正文查看权限时才读取并披露固定片段及含原文的裁定依据。
        var chunks = evidenceVisible
                ? builds.chunks(run.buildId()).stream().collect(Collectors.toMap(Chunk::ordinal, Function.identity()))
                : Map.<Integer, Chunk>of();
        return runs.questions(run.id()).stream()
                .map(row -> {
                    var content = content(row);
                    var matches = !evidenceVisible || row.observedChunks() == null
                            ? List.<Fragment>of()
                            : List.of(json.read(row.observedChunks(), Integer[].class)).stream()
                                    .map(chunks::get)
                                    .map(chunk -> new Fragment(
                                            chunk.ordinal(),
                                            chunk.body(),
                                            List.of(json.read(chunk.evidence(), Evidence[].class)),
                                            chunk.digest()))
                                    .toList();
                    var judgment = row.decision() == null
                            ? null
                            : new KnowledgeValidationVO.Judgment(
                                    row.decision(),
                                    evidenceVisible ? row.reason() : null,
                                    Ids.text(row.decidedBy()),
                                    Times.instant(row.decidedAt()));
                    return new KnowledgeValidationVO.Question(
                            Ids.text(row.id()),
                            Ids.text(row.testId()),
                            Ids.text(row.testRevisionId()),
                            row.revision(),
                            content.getMode(),
                            content.getQuestion(),
                            "NUMERIC".equals(content.getMode())
                                    ? String.valueOf(content.getExpectedCount())
                                    : content.getExpectedExplanation(),
                            content.getEvidence(),
                            row.actualCount() == null ? null : row.actualCount().toString(),
                            row.statementText(),
                            row.failureCode(),
                            row.state(),
                            Ids.text(row.executorId()),
                            matches,
                            judgment);
                })
                .toList();
    }

    /**
     * 输出运行所用的确切构建和候选。
     */
    private KnowledgeValidationVO.Run viewRun(Run run) {
        return new KnowledgeValidationVO.Run(
                run.id(), Ids.text(run.candidateId()), run.buildId(), run.state(), Times.instant(run.createdAt()));
    }

    /**
     * 阻断已替换、超时、撤权或已取消的运行。
     */
    private Build active(Source source, Run run, int version) {
        if (version != 1 || !running(run) || remaining(run) <= 0) {
            throw new BizIllegalException(ErrorCode.STALE_RUN, "当前验证运行已失效");
        }
        if (run.recoveryPauseId() != null) {
            // 工作者交付前固定的恢复目的和发布绑定仍须有效。
            return verifyRecoveryRun(source, run);
        }
        var candidate = builds.candidate(source.id(), run.candidateId());
        if (candidate == null
                || !"VALIDATING".equals(candidate.getStatus())
                || !run.id().equals(candidate.getCurrentValidationId())
                || !Objects.equals(source.configurationId(), run.connectionRevisionId())) {
            throw new BizIllegalException(ErrorCode.STALE_RUN, "当前验证运行已失效");
        }
        var build = builds.get(run.buildId());
        return actors.asActor(
                run.requestedBy(),
                run.authenticationVersion(),
                () -> {
                    verifyBasis(source, build, run.candidateId());
                    return build;
                },
                "releases.view",
                run.operationCode());
    }

    /**
     * 恢复以使用中的实际绑定为依据，复用它的已核实索引。
     */
    Build recoveryBuild(Source source, Binding binding) {
        versionUse.requireBasis(source, binding);
        var build = builds.get(binding.buildId());
        if (!transport.isEnabled()
                || !index.getModelSourceIds().contains(source.id())
                || build == null
                || !"SUCCEEDED".equals(build.state())
                || build.candidateId() != binding.candidateId()) {
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "当前正式索引或来源执行资格已失效");
        }
        return build;
    }

    /**
     * 普通问数始终采用当前限制。单一恢复运行沿可信内部任务入口获得本轮隔离资格。
     */
    Build verifyRecoveryRun(Source source, Run run) {
        var binding = versionUse.current(source.id(), run.recoveryBindingId());
        var pause = requirePause(binding, run.recoveryPauseId());
        if (!Objects.equals(pause.currentRunId(), run.id())
                || !Objects.equals(binding.buildId(), run.buildId())
                || binding.candidateId() != run.candidateId()
                || !Objects.equals(source.configurationId(), run.connectionRevisionId())) {
            throw new BizIllegalException(ErrorCode.STALE_RUN, "正式版本、暂停记录或连接已变化，请重新验证");
        }
        var questions = runs.questions(run.id());
        var frozen = questions.stream().map(Question::testRevisionId).sorted().toList();
        var current = preflight.requireRelatedTests(source.id(), binding.documentId(), binding.documentRevisionId());
        if (!frozen.equals(current)) {
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "已审核题单已变化，请重新运行恢复验证");
        }
        if (questions.stream()
                .filter(row -> row.authenticationVersion() != null)
                .anyMatch(row -> !authorizedQuestion(source, row))) {
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "验证执行身份或数据权限已变化，请重新验证");
        }
        return actors.asActor(
                run.requestedBy(),
                run.authenticationVersion(),
                () -> {
                    // 发起人失去页面或数据资格后，隔离输入与旧结果都不能继续使用。
                    requireReader(source.id());
                    return recoveryBuild(source, binding);
                },
                "releases.view",
                "releases.test");
    }

    /**
     * 将失效原因交给可查看的版本详情，旧结果继续可追溯。
     */
    String recoveryIssue(Source source, Run run) {
        try {
            verifyRecoveryRun(source, run);
            return null;
        } catch (BizIllegalException stale) {
            return stale.getMessage();
        }
    }

    /**
     * 候选资格仍使用构建时的文档、治理、题单、模型与基础入口。
     */
    private void verifyBasis(Source source, Build build, long candidateId) {
        var candidate = builds.candidate(source.id(), candidateId);
        if (!transport.isEnabled()
                || !index.getModelSourceIds().contains(source.id())
                || build == null
                || candidate == null
                || !"SUCCEEDED".equals(build.state())
                || !build.id().equals(candidate.getCurrentBuildId())) {
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "候选索引或来源执行资格已失效");
        }
        preflight.verifyFrozen(source, build.documentId(), candidate, builds.testRevisions(candidateId));
    }

    /**
     * 对当前有效来源做最小披露，尚未支持确定执行的限制便拒绝处理。
     */
    private String authorization(long source, long executor, long auth) {
        actors.require(executor, auth, "test-cases.review", "test-cases.view");
        var scope = access.requireSource(executor, source);
        if (!index.allowsValidationRules(source, scope.getRules())) {
            throw BizIllegalException.denied();
        }
        var origins = scope.getOrigins().stream()
                .map(item -> item.getKind() + ":" + item.getId())
                .sorted()
                .toList();
        var rules = scope.getRules().stream()
                .map(rule -> rule.getId() + "/" + rule.getVersion())
                .sorted()
                .toList();
        return DigestUtil.sha256Hex(fingerprints.canonical(List.of(origins, rules), false));
    }

    /**
     * 不延用旧账户、规则或来源集合。
     */
    private boolean authorizedQuestion(Source source, Question row) {
        try {
            return row.authenticationVersion() != null
                    && Objects.equals(
                            row.accessDigest(),
                            authorization(source.id(), row.executorId(), row.authenticationVersion()));
        } catch (BizIllegalException denied) {
            return false;
        }
    }

    /**
     * 实际证据按查看者自己的当前范围回读，不继承执行人的访问权。
     */
    private boolean canRead(long source) {
        return permissions.has("test-cases.view")
                && access.findSource(UserContext.getUser(), source)
                        .map(scope -> index.allowsValidationRules(source, scope.getRules()))
                        .orElse(false);
    }

    /**
     * 裁定前先确认本人可以回读本次证据。
     */
    void requireReader(long source) {
        permissions.require("releases.view");
        permissions.require("knowledge.view");
        if (!canRead(source)) {
            throw BizIllegalException.denied();
        }
    }

    /**
     * 运行必须来自路径表达的原始发布任务。
     */
    Run forTask(long source, long task, String id) {
        var run = readRun(id);
        if (run.sourceId() != source || run.taskId() != task) {
            throw BizIllegalException.missing();
        }
        return run;
    }

    /**
     * 对不存在的任务统一返回同一业务语义。
     */
    Run readRun(String id) {
        var run = runs.get(id);
        if (run == null) {
            throw BizIllegalException.missing();
        }
        return run;
    }

    /**
     * 任务详情和写入均检查原始来源。
     */
    Task task(long source, long id) {
        var task = tasks.get(source, id);
        if (task == null) {
            throw BizIllegalException.missing();
        }
        return task;
    }

    /**
     * 运行或裁定都不能越过任务聚合版本。
     */
    private void version(Task task, int required) {
        if (task.version() != required) {
            throw BizIllegalException.conflict();
        }
    }

    /**
     * 只在已有题的确切固定修订中读取审核内容。
     */
    private Content content(Question question) {
        return json.read(question.content(), Content.class);
    }

    /**
     * 查询尚未执行的审核题。
     */
    private boolean pending(Question question) {
        return "PENDING".equals(question.state());
    }

    /**
     * 判断尚能回收工作结果的运行。
     */
    boolean running(Run run) {
        return Set.of("QUEUED", "RUNNING").contains(run.state());
    }

    /**
     * 预算从持久接收时间起算，重启与重投都不会延长。
     */
    int remaining(Run run) {
        return Math.max(
                0,
                SECONDS
                        - (int) Duration.between(run.createdAt(), LocalDateTime.now(ZoneOffset.UTC))
                                .toSeconds());
    }

    /**
     * 显式请求的回执与审计在同一事务中记录。
     */
    void audit(String command, String action, String type, String id, String name, String reason) {
        audit.record(AuditWriteDTO.builder()
                .commandId(command)
                .actionCode(action)
                .targetType(type)
                .targetId(id)
                .targetName(name)
                .reason(reason)
                .securityVersion(commands.auditSecurityVersion(false))
                .build());
    }

    /**
     * 不向用户或日志传播源端 JDBC 异常正文。
     */
    private static final class QueryFailure extends RuntimeException {}
}
