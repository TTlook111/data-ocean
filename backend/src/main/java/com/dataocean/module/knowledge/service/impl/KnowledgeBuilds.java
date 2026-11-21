package com.dataocean.module.knowledge.service.impl;

import com.dataocean.common.Ids;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.governance.config.GovernanceAiProperties;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ServiceActorPermission;
import com.dataocean.module.knowledge.entity.dto.KnowledgeReleaseBuildDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeReleaseCancelDTO;
import com.dataocean.module.knowledge.entity.po.KnowledgeReleaseCandidatePO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeDocumentVO.Content;
import com.dataocean.module.knowledge.entity.vo.KnowledgeReleaseBuildVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeReleaseDetailVO;
import com.dataocean.module.knowledge.mapper.KnowledgeBuildMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeBuildMapper.Build;
import com.dataocean.module.knowledge.mapper.KnowledgeBuildMapper.Chunk;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeReleaseCandidateMapper;
import com.dataocean.module.knowledge.service.KnowledgeBuildService;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Evidence;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Fragment;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Input;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Progress;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Receipt;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Status;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Verified;
import com.dataocean.module.knowledge.service.KnowledgeIndexProperties;
import com.dataocean.module.knowledge.service.KnowledgeReleasePreflight;
import com.dataocean.module.model.service.ModelRegistry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;

/**
 * 构建事务固定完整片段。只有当前尝试和确切回读证明能进入待验证阶段。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBuilds implements KnowledgeBuildService {

    /**
     * 发布准备与构建的既有操作权限。
     */
    private static final String ACTION = "releases.build";

    /**
     * 排队与构建共享的单次活动时限，失败后由用户开启另一代次。
     */
    private static final int SECONDS = 180;

    /**
     * 队列回执只接受可对用户披露的稳定原因。
     */
    private static final Set<String> FAILURES = Set.of(
            "MODEL_FORMAT",
            "MODEL_AUTH",
            "MODEL_RATE_LIMIT",
            "MODEL_RESULT_UNKNOWN",
            "INDEX_UNAVAILABLE",
            "INDEX_READBACK_MISMATCH",
            "INPUT_STALE",
            "BUILD_INTERRUPTED",
            "BUILD_TIMEOUT");

    /**
     * 持久任务、片段及代次竞争条件。
     */
    private final KnowledgeBuildMapper builds;

    /**
     * 读确认正文及其精确章节证据。
     */
    private final KnowledgeDocumentMapper documents;

    /**
     * 权威的业务范围和当前基础入口。
     */
    private final KnowledgeReleaseCandidateMapper candidates;

    /**
     * 稳定来源锁和当前适用目录。
     */
    private final GovernanceCatalog catalog;

    /**
     * 复用候选准备的依赖、题单和模型检查。
     */
    private final KnowledgeReleasePreflight preflight;

    /**
     * 拿到许可时从当前系统模型配置解析固定修订凭据。
     */
    private final ModelRegistry models;

    /**
     * 与治理共用的内部服务启用状态。
     */
    private final GovernanceAiProperties transport;

    /**
     * 仅向部署已许可来源外发知识片段。
     */
    private final KnowledgeIndexProperties index;

    /**
     * 用户提交使用统一许可、命令及审计链。
     */
    private final PermissionService permissions;

    /**
     * 工作入口在每个敏感步骤核对原任务执行人。
     */
    private final ServiceActorPermission actors;

    /**
     * 接收命令和实际写入处于同一事务。
     */
    private final MutationService commands;

    /**
     * 保存构建受理事实及其关联操作人。
     */
    private final AuditService audit;

    /**
     * 原数据以项目统一 JSON 编解码格式读取。
     */
    private final JsonCodec json;

    /**
     * 固定片段摘要用规范化正文及证据计算。
     */
    private final Fingerprints fingerprints;

    /**
     * 受理状态、构建代次及其片段原子落库，随后独立派发。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO start(long source, long id, String command, KnowledgeReleaseBuildDTO input) {
        return commands.execute(
                ACTION,
                command,
                "knowledge-build:" + source + "/" + id,
                input,
                input.getExpectedVersion(),
                false,
                false,
                () -> {
                    // 只有有权限且仍有效的当前候选可进入下一次执行。
                    permissions.require("releases.view");
                    var origin = catalog.source(source, true);
                    var candidate = candidate(source, id);
                    verifyVersion(candidate, input);
                    if (!Set.of("PREPARED", "BUILD_FAILED").contains(candidate.getStatus())) {
                        throw BizIllegalException.state("当前发布任务不能开始构建");
                    }
                    long documentId = builds.document(candidate.getScopeId());
                    verifyFrozen(origin, documentId, candidate);

                    String build = UUID.randomUUID().toString();
                    builds.insert(
                            build,
                            id,
                            index.collection(build),
                            UserContext.getUser(),
                            UserContext.current().authenticationVersion());
                    if (builds.begin(id, build, candidate.getVersion()) != 1) {
                        throw BizIllegalException.conflict();
                    }
                    saveChunks(build, documentId, candidate.getDocumentRevisionId());
                    audit.record(AuditWriteDTO.builder()
                            .actionCode(ACTION)
                            .targetType("knowledge_index_build")
                            .targetId(build)
                            .targetName(candidate.getTitle())
                            .commandId(command)
                            .securityVersion(commands.auditSecurityVersion(false))
                            .build());
                    return new MutationVO(command, "knowledge_index_build", build, candidate.getVersion() + 1);
                });
    }

    /**
     * 取消后拒绝迟到回执，已保存构建记录和正在使用的版本继续保留。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO cancel(long source, long id, String command, KnowledgeReleaseCancelDTO input) {
        return commands.execute(
                ACTION,
                command,
                "knowledge-cancel:" + source + "/" + id,
                input,
                input.getExpectedVersion(),
                false,
                false,
                () -> {
                    // 取消与开始构建、回执应用使用同一来源事务锁。
                    permissions.require("releases.view");
                    catalog.source(source, true);
                    var row = candidate(source, id);
                    verifyVersion(row, input);
                    if (builds.cancel(id, row.getVersion()) != 1) {
                        throw BizIllegalException.state("当前发布任务不能取消");
                    }
                    if (row.getCurrentBuildId() != null) {
                        // 仍在执行的工作者在下一步回查或交付时被阻断。
                        builds.cancelAttempt(row.getCurrentBuildId());
                    }
                    audit.record(AuditWriteDTO.builder()
                            .actionCode(ACTION)
                            .targetType("knowledge_release")
                            .targetId(Ids.text(id))
                            .targetName(row.getTitle())
                            .reason(input.getReason().strip())
                            .commandId(command)
                            .securityVersion(commands.auditSecurityVersion(false))
                            .build());
                    return new MutationVO(command, "knowledge_release", Ids.text(id), row.getVersion() + 1);
                });
    }

    /**
     * 版本冲突由前端回读后重新办理。
     */
    private void verifyVersion(KnowledgeReleaseCandidatePO candidate, KnowledgeReleaseBuildDTO input) {
        if (!candidate.getVersion().equals(input.getExpectedVersion())
                || !candidate.getFingerprint().equals(input.getFingerprint())) {
            throw BizIllegalException.conflict();
        }
    }

    /**
     * 当前源、固定确认及历次记录使用同一个读取快照。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public KnowledgeReleaseDetailVO detail(long source, long id) {
        var row = candidate(source, id);
        long document = builds.document(row.getScopeId());
        var revision = documents.revision(document, row.getDocumentRevisionId());
        var history = builds.history(id).stream()
                .map(task -> new KnowledgeReleaseBuildVO(
                        task.id(),
                        task.state(),
                        task.failureCode(),
                        task.verifiedChunks(),
                        task.vectorDimension(),
                        Times.instant(task.createdAt())))
                .toList();
        return new KnowledgeReleaseDetailVO(
                catalog.source(source, false).name(),
                Ids.text(document),
                revision.revision(),
                KnowledgeReleases.view(row),
                builds.testRevisions(id).size(),
                history);
    }

    /**
     * SSE 阶段从实际候选与当次构建回读，不包含业务正文或凭据。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Progress progress(long source, long id) {
        var row = candidate(source, id);
        var build = row.getCurrentBuildId() == null ? null : builds.get(row.getCurrentBuildId());
        return new Progress(
                row.getCurrentBuildId(), row.getVersion(), row.getStatus(), build == null ? null : build.state());
    }

    /**
     * 一次代次只解密并交付一次真实模型许可。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Input claimInput(String id, int runVersion) {
        var task = task(id, runVersion);
        var origin = catalog.source(task.sourceId(), true);
        var candidate = authorized(task, origin);
        if (builds.claimModel(id) != 1) {
            // 曾经外发的请求在结果不明确时不能由重投再次调用。
            throw new BizIllegalException(ErrorCode.EXECUTION_UNCERTAIN, "原构建执行须核实");
        }
        var model = models.invocation(candidate.getEmbeddingRevisionId(), remaining(task));
        var fragments = builds.chunks(id).stream()
                .map(chunk -> new Fragment(
                        chunk.ordinal(),
                        chunk.body(),
                        List.of(json.read(chunk.evidence(), Evidence[].class)),
                        chunk.digest()))
                .toList();
        var question = content(task.documentId(), candidate.getDocumentRevisionId())
                .getQuestions()
                .get(0);
        return new Input(
                Ids.text(candidate.getId()),
                Ids.text(candidate.getDocumentRevisionId()),
                candidate.getFingerprint(),
                task.collectionName(),
                question,
                fragments,
                model,
                remaining(task));
    }

    /**
     * 读取当前资格以阻断剩余向量调用和索引写入。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recheck(String id, int runVersion) {
        var initial = task(id, runVersion);
        var origin = catalog.source(initial.sourceId(), true);
        authorized(task(id, runVersion), origin);
    }

    /**
     * 多进程调度只在短事务中登记派发租约。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Build claimDispatch() {
        String id = builds.next();
        if (id == null) {
            return null;
        }
        builds.reserve(id, UUID.randomUUID().toString());
        return builds.get(id);
    }

    /**
     * 回包未确定时只回读原任务；结果、当前尝试与权威资格同时核对。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void apply(Build dispatched, Status status) {
        if (remaining(dispatched) > 0
                && (status == null
                        || status.runVersion() != 1
                        || !Set.of("FAILED", "SUCCEEDED").contains(status.state()))) {
            // 未接收、排队或丢失回包都保留同一请求定位及预算。
            builds.postpone(dispatched.id(), dispatched.dispatchToken());
            return;
        }
        var source = catalog.source(dispatched.sourceId(), true);
        var current = builds.get(dispatched.id());
        if (!Objects.equals(current.dispatchToken(), dispatched.dispatchToken())
                || !Set.of("QUEUED", "RUNNING").contains(current.state())) {
            // 旧派发结果不能推进已经结束的代次。
            return;
        }
        if (remaining(current) <= 0) {
            finish(current, null, "BUILD_TIMEOUT");
            return;
        }
        if ("FAILED".equals(status.state())) {
            String code = status.failureCode();
            finish(current, null, code != null && FAILURES.contains(code) ? code : "BUILD_INTERRUPTED");
            return;
        }
        KnowledgeReleaseCandidatePO candidate;
        try {
            // 外发之后撤权、改绑或治理变化也会阻断结果认可。
            candidate = authorized(current, source);
        } catch (BizIllegalException stale) {
            finish(current, null, "INPUT_STALE");
            return;
        }
        Receipt receipt = status.result();
        if (!goodReceipt(current, candidate, receipt)) {
            finish(current, null, "INDEX_READBACK_MISMATCH");
            return;
        }
        finish(current, receipt, null);
    }

    /**
     * 对候选就绪只认可当前代次的完整 Strong 回执。
     */
    private boolean goodReceipt(Build task, KnowledgeReleaseCandidatePO candidate, Receipt receipt) {
        if (receipt == null
                || !task.collectionName().equals(receipt.collection())
                || !candidate.getFingerprint().equals(receipt.fingerprint())
                || receipt.dimension() <= 0
                || receipt.observed() == null
                || receipt.hits() == null
                || receipt.hits().isEmpty()) {
            return false;
        }
        var expected = builds.chunks(task.id()).stream()
                .map(chunk -> new Verified(chunk.ordinal(), chunk.digest()))
                .toList();
        var ids = expected.stream().map(Verified::ordinal).toList();
        return expected.equals(receipt.observed())
                && ids.containsAll(receipt.hits())
                && new HashSet<>(receipt.hits()).size() == receipt.hits().size();
    }

    /**
     * 只推进候选和构建尝试，不写正式入口或任何发布结果。
     */
    private void finish(Build task, Receipt receipt, String error) {
        if (builds.finish(
                        task.id(),
                        task.dispatchToken(),
                        receipt == null ? "FAILED" : "SUCCEEDED",
                        error,
                        receipt == null ? null : receipt.dimension(),
                        receipt == null ? null : receipt.observed().size(),
                        receipt == null ? null : json.write(receipt.hits()))
                == 1) {
            builds.end(task.candidateId(), task.id(), receipt == null ? "BUILD_FAILED" : "READY_FOR_VALIDATION");
        }
    }

    /**
     * 从当前状态和可信发起人核对候选依赖，证据读取沿用原本人规则。
     */
    private KnowledgeReleaseCandidatePO authorized(Build task, Source source) {
        var candidate = candidate(source.id(), task.candidateId());
        if (remaining(task) <= 0
                || !"BUILDING".equals(candidate.getStatus())
                || !task.id().equals(candidate.getCurrentBuildId())) {
            throw new BizIllegalException(ErrorCode.STALE_RUN, "当前构建尝试已失效");
        }
        return actors.asActor(
                task.requestedBy(),
                task.authenticationVersion(),
                () -> {
                    verifyFrozen(source, task.documentId(), candidate);
                    return candidate;
                },
                "releases.view",
                ACTION);
    }

    /**
     * 当前治理、题单、模型或基础绑定变化时要求重新准备候选。
     */
    private void verifyFrozen(Source source, long document, KnowledgeReleaseCandidatePO candidate) {
        if (!transport.isEnabled() || !index.getModelSourceIds().contains(source.id())) {
            throw BizIllegalException.state("当前数据源未配置知识模型传输范围");
        }
        var check = preflight.check(source, preflight.document(source.id(), document));
        var scope = candidates.scope(source.id(), document);
        if (!check.issues().isEmpty()
                || !Objects.equals(candidate.getDocumentRevisionId(), check.documentRevision())
                || !Objects.equals(candidate.getEmbeddingRevisionId(), check.embeddingRevision())
                || !Objects.equals(candidate.getBaseBindingId(), scope.activeBindingId())
                || !builds.testRevisions(candidate.getId()).equals(check.testRevisions())) {
            throw new BizIllegalException(
                    ErrorCode.DEPENDENCY_STALE,
                    check.issues().isEmpty() ? "候选依据已变化，请重新核对并创建候选" : String.join("；", check.issues()));
        }
    }

    /**
     * 章节不是任意长度切分，业务用途和使用边界随每一段现有正文固定。
     */
    private void saveChunks(String build, long document, long revision) {
        var body = content(document, revision);
        var references = documents.references(revision);
        for (int number = 0; number < body.getSections().size(); number++) {
            // 编号与原确认章节相同，必要依赖沿现有引用记录完整纳入。
            final int ordinal = number;
            var section = body.getSections().get(number);
            var evidence = references.stream()
                    .filter(ref -> ref.sectionIndex() == ordinal)
                    .map(ref -> new Evidence(
                            Ids.text(ref.objectId()),
                            ref.generation(),
                            ref.kind(),
                            Ids.text(ref.targetId()),
                            Ids.text(ref.factRevisionId()),
                            Ids.text(ref.structureRevisionId())))
                    .distinct()
                    .toList();
            if (evidence.isEmpty()) {
                throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "确认章节缺少治理依据");
            }
            String text = String.join(
                    "\n",
                    body.getTitle(),
                    "用途：" + body.getPurpose(),
                    "适用边界：" + body.getExclusions(),
                    section.getTitle(),
                    section.getBody());
            String digest = DigestUtil.sha256Hex(fingerprints.canonical(List.of(text, evidence), false));
            builds.addChunk(build, new Chunk(number, text, json.write(evidence), digest));
        }
    }

    /**
     * 始终按确切修订回读正文。
     */
    private Content content(long document, long revision) {
        return json.read(documents.revision(document, revision).content(), Content.class);
    }

    /**
     * 候选只能从其所属来源读取。
     */
    private KnowledgeReleaseCandidatePO candidate(long source, long id) {
        var row = builds.candidate(source, id);
        if (row == null) {
            throw BizIllegalException.missing();
        }
        return row;
    }

    /**
     * 工作者只能读取当前存在且尚在途的尝试。
     */
    private Build task(String id, int runVersion) {
        var row = builds.get(id);
        if (row == null || runVersion != 1 || !Set.of("QUEUED", "RUNNING").contains(row.state())) {
            throw new BizIllegalException(ErrorCode.STALE_RUN, "构建尝试不存在或已结束");
        }
        return row;
    }

    /**
     * 排队时间与执行时间共用一个有限的活动预算。
     */
    private int remaining(Build task) {
        return Math.toIntExact(Duration.between(
                        LocalDateTime.now(ZoneOffset.UTC), task.createdAt().plusSeconds(SECONDS))
                .toSeconds());
    }
}
