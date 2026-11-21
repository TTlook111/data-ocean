package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.iam.entity.po.OutboxPO;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.OutboxMapper;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ReviewService;
import com.dataocean.module.knowledge.entity.dto.KnowledgePublishDTO.Confirm;
import com.dataocean.module.knowledge.entity.vo.KnowledgeDocumentVO.Content;
import com.dataocean.module.knowledge.entity.vo.KnowledgePublicationVO.Detail;
import com.dataocean.module.knowledge.entity.vo.KnowledgePublicationVO.Outcome;
import com.dataocean.module.knowledge.entity.vo.KnowledgePublicationVO.Preview;
import com.dataocean.module.knowledge.entity.vo.KnowledgePublicationVO.Version;
import com.dataocean.module.knowledge.mapper.KnowledgeBuildMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeBuildMapper.Build;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.InsertBinding;
import com.dataocean.module.knowledge.mapper.KnowledgeReleaseCandidateMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeReleaseTaskMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeReleaseTaskMapper.Task;
import com.dataocean.module.knowledge.service.KnowledgeIndexProperties;
import com.dataocean.module.knowledge.service.KnowledgeReferences;
import com.dataocean.module.model.service.ModelRegistry;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 发布检查、正式绑定切换、切换后核实及历史回读。
 */
@Service
@RequiredArgsConstructor
public class KnowledgePublications {

    /**
     * 正式发布和核实的实际操作能力。
     */
    private static final String OPERATION = "releases.publish";

    /**
     * 命令已写入，需独立回读才能确认本次实际结果。
     */
    private static final String PENDING = "PUBLISH_RECONCILE";

    /**
     * 按源读取并锁定治理、构建和发布共同依赖的当前事实。
     */
    private final GovernanceCatalog catalog;

    /**
     * 确认预检输入使用当前任务版本。
     */
    private final KnowledgeReleaseTaskMapper tasks;

    /**
     * 读取当前候选、实际构建，以及持久化的同版证据。
     */
    private final KnowledgeBuildMapper builds;

    /**
     * 读取单文档发布范围和当前权威指针。
     */
    private final KnowledgeReleaseCandidateMapper candidates;

    /**
     * 正式清单及直接上一版归属。
     */
    private final KnowledgePublicationMapper bindings;

    /**
     * 发布前核对全部逐题证据和当前有效的执行资格。
     */
    private final KnowledgeValidations validations;

    /**
     * 预检和核实都需要实际索引的独立回读。
     */
    private final KnowledgeIndexReadback indexes;

    /**
     * 验证和确认共用对实际变更签发的复核凭据。
     */
    private final ReviewService reviews;

    /**
     * 发布命令及实际记录同事务提交。
     */
    private final MutationService commands;

    /**
     * 自动核实与命令办理使用相同授权变更顺序。
     */
    private final GuardMapper guard;

    /**
     * 查询和写操作继续执行现有页面及文档权限。
     */
    private final PermissionService permissions;

    /**
     * 预检和正式正文查看遵循本人实际来源授权。
     */
    private final AccessScopeService access;

    /**
     * 与标准题回读共用逐源输出规则准入。
     */
    private final KnowledgeIndexProperties settings;

    /**
     * 只回读绑定实际使用的文档和必要引用。
     */
    private final KnowledgeDocumentMapper documents;

    /**
     * 在版本界面表达实际当前治理依赖是否仍然有效。
     */
    private final KnowledgeReferences references;

    /**
     * 当前绑定实际固定的向量模型可能在发布后停用。
     */
    private final ModelRegistry models;

    /**
     * 保存发布提交及核实动作的审计。
     */
    private final AuditService audit;

    /**
     * 同一提交保存权威入口切换通知。
     */
    private final OutboxMapper outbox;

    /**
     * 发布事件和固定正文不混入当前编辑草稿。
     */
    private final JsonCodec json;

    /**
     * 最新权威状态及实际索引通过时才签发核对凭据。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(long sourceId, long id, int expected) {
        requirePublisher(sourceId);
        var source = catalog.source(sourceId, false);
        var checked = check(source, task(sourceId, id), expected);
        var fixed = checked.basis();
        int count = verifyIndex(checked);
        var token = reviews.preview(
                        OPERATION,
                        key(sourceId, id),
                        expected,
                        fixed,
                        List.of(new ChangeVO(
                                "binding",
                                "生效版本",
                                checked.beforeRevision() == null ? "首次发布" : "r" + checked.beforeRevision(),
                                "r" + checked.revision())),
                        List.of(),
                        List.of())
                .getReviewToken();
        return new Preview(
                checked.title(),
                checked.beforeRevision(),
                checked.revision(),
                Ids.text(fixed.candidate()),
                fixed.build(),
                fixed.run(),
                count,
                token);
    }

    /**
     * 在一个平台库事务中写版本、切换权威指针，保存同一命令的审计及可靠事件。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO publish(long sourceId, long id, String command, Confirm request) {
        int expected = request.getExpectedVersion();
        return commands.execute(OPERATION, command, key(sourceId, id), request, expected, false, false, () -> {
            // 依赖写入和入口切换在同一来源锁内串行。
            requirePublisher(sourceId);
            var source = catalog.source(sourceId, true);
            var checked = check(source, task(sourceId, id), expected);
            var proof = checked.basis();
            reviews.verify(OPERATION, key(sourceId, id), expected, proof, request.getReviewToken());
            verifyIndex(checked);
            // 索引回读可能需要等待，在实际切换前锁住仍匹配的模型与节点配置。
            models.lockCurrentBinding("rag-embedding", proof.embeddingRevision());

            long bindingId = IdWorker.getId();
            String key = UUID.fromString(command).toString();
            bindings.insert(new InsertBinding(
                    bindingId,
                    proof.scope(),
                    proof.candidate(),
                    proof.build(),
                    proof.run(),
                    proof.documentRevision(),
                    proof.embeddingRevision(),
                    proof.baseBinding(),
                    UserContext.getUser(),
                    key));
            if (bindings.switchBinding(sourceId, proof.scope(), proof.baseBinding(), bindingId) != 1) {
                // 竞争导致的旧基础不得改写已经生效的版本。
                throw staleBase();
            }
            if (bindings.advance(proof.candidate(), "READY_TO_PUBLISH", PENDING) != 1) {
                // 正式切换与发布阶段变更必须一致提交。
                throw BizIllegalException.conflict();
            }

            var before = checked.beforeRevision() == null ? "首次发布" : "r" + checked.beforeRevision();
            audit(key, bindingId, checked.title(), new ChangeVO("binding", "生效修订", before, "r" + checked.revision()));
            var event = new SwitchEvent(
                    Ids.text(sourceId),
                    Ids.text(proof.scope()),
                    Ids.text(bindingId),
                    Ids.text(proof.baseBinding()),
                    Ids.text(proof.documentRevision()),
                    proof.build(),
                    Ids.text(proof.embeddingRevision()));
            outbox.append(new OutboxPO(
                    UUID.randomUUID().toString(),
                    "knowledge.binding.switched",
                    1,
                    "knowledge_release",
                    Ids.text(id),
                    expected + 1,
                    key,
                    json.write(event),
                    "PENDING",
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
            return new MutationVO(key, "knowledge_release_binding", Ids.text(bindingId), expected + 1);
        });
    }

    /**
     * 原命令提交后主动核实。独立事务只确认既有实际入口，失败后仍可按原编号核实。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void verifyCommitted(long sourceId, long taskId, long bindingId) {
        // 自动回读可能发生在重放旧命令时，仍须在真实锁下复核当前权限。
        guard.shared();
        catalog.source(sourceId, true);
        if (!canPublish(sourceId)) {
            // 确认期间失权由仍有资格者后续办理。
            return;
        }
        var original = bindings.published(sourceId, taskId);
        if (original != null && original.id() == bindingId) {
            // 重复的原命令也只验证它对应的绑定。
            confirmActual(sourceId, task(sourceId, taskId), original);
        }
    }

    /**
     * 对结果不明确的原任务实际回读，只确认既有入口而不再次切换。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO reconcile(long sourceId, long id, String command, int expected) {
        return commands.execute(
                OPERATION,
                command,
                "knowledge-publish-result:" + sourceId + "/" + id,
                new RecheckRequest(expected),
                expected,
                false,
                false,
                () -> {
                    // 临时失败不丢失原正式绑定及待核实记录。
                    catalog.source(sourceId, true);
                    requirePublisher(sourceId);
                    var task = task(sourceId, id);
                    version(task, expected);
                    var original = bindings.published(sourceId, id);
                    if (original == null) {
                        // 没有提交记录的任务保持原阶段，不能凭核实动作生效。
                        throw BizIllegalException.state("当前没有已提交的版本切换");
                    }
                    boolean verified = confirmActual(sourceId, task, original);
                    if (verified) {
                        // 核实操作只写一次独立的确认审计。
                        audit(
                                UUID.fromString(command).toString(),
                                original.id(),
                                original.title(),
                                new ChangeVO("verification", "切换结果", "待核实", "已核实"));
                    }
                    return new MutationVO(
                            command,
                            "knowledge_release_binding",
                            Ids.text(original.id()),
                            verified ? expected + 1 : expected);
                });
    }

    /**
     * 用原发布、权威入口和实际存储再次比对，未核实的历史不能被另一次发布覆盖。
     */
    private boolean confirmActual(long source, Task task, Binding binding) {
        if (!PENDING.equals(task.status())
                || binding.verifiedAt() != null
                || !Objects.equals(binding.activeBindingId(), binding.id())
                || !Objects.equals(task.candidateId(), binding.candidateId())) {
            // 不把新版或其他范围的实际生效状态写回原任务。
            return false;
        }
        var fixed = builds.candidate(source, binding.candidateId());
        var build = builds.get(binding.buildId());
        if (fixed == null
                || build == null
                || !"SUCCEEDED".equals(build.state())
                || build.candidateId() != binding.candidateId()
                || !Objects.equals(fixed.getScopeId(), binding.scopeId())
                || !Objects.equals(fixed.getCurrentBuildId(), binding.buildId())
                || !Objects.equals(fixed.getCurrentValidationId(), binding.validationId())
                || !Objects.equals(fixed.getDocumentRevisionId(), binding.documentRevisionId())
                || !Objects.equals(fixed.getEmbeddingRevisionId(), binding.embeddingRevisionId())) {
            // 持久化组合本身不一致时继续显示待核实。
            return false;
        }
        try {
            indexes.verify(build, binding.documentRevisionId(), binding.embeddingRevisionId(), fixed.getFingerprint());
        } catch (BizIllegalException unverified) {
            // 存储不可读并不意味着此前 MySQL 没有提交。
            return false;
        }
        if (bindings.markVerified(binding.id()) != 1
                || bindings.advance(binding.candidateId(), PENDING, "PUBLISHED") != 1) {
            // 核实状态及真实时间需要同时落库。
            throw BizIllegalException.conflict();
        }
        return true;
    }

    /**
     * 任务结果按本次发布快照回读，同时呈现实际生效版本。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Outcome result(long sourceId, long taskId) {
        var source = catalog.source(sourceId, false);
        var task = task(sourceId, taskId);
        var published = bindings.published(sourceId, taskId);
        if (published == null) {
            // 仅有已验证候选时不伪造正式版本。
            return new Outcome(task.status(), null, null, null, null, false);
        }
        var active = linked(sourceId, published.activeBindingId(), published.scopeId());
        var previous = linked(sourceId, published.previousBindingId(), published.scopeId());
        String issue = PENDING.equals(task.status()) ? "待核实实际生效入口及同版索引" : null;
        return new Outcome(
                task.status(),
                view(source, published),
                view(source, active),
                view(source, previous),
                issue,
                PENDING.equals(task.status()) && canPublish(sourceId));
    }

    /**
     * 在指定来源回读当前生效版本的稳定分页。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<Version> list(long sourceId, Long scope, String keyword, String object, PageQuery page) {
        var source = catalog.source(sourceId, false);
        String term = keyword == null ? "" : keyword.strip();
        String objectTerm = object == null ? "" : object.strip();
        return PageVO.from(
                bindings.current(page.toFixedOrderMpPage(), sourceId, scope, term, objectTerm),
                row -> view(source, row));
    }

    /**
     * 只从同范围的真实发布绑定取得正式正文和直接上一版。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(long sourceId, long bindingId) {
        var source = catalog.source(sourceId, false);
        var requested = bindings.get(sourceId, bindingId);
        if (requested == null) {
            // 另一来源的正式版本不可从当前路径回读。
            throw BizIllegalException.missing();
        }
        var previous = linked(sourceId, requested.previousBindingId(), requested.scopeId());
        boolean readable = permissions.has("knowledge.view") && hasSource(sourceId);
        return new Detail(
                view(source, requested),
                view(source, previous),
                readable ? content(requested) : null,
                readable && previous != null ? content(previous) : null);
    }

    /**
     * 发布前的业务校验按实际版本、源状态与各题结果执行。
     */
    private Checked check(Source source, Task task, int expected) {
        version(task, expected);
        if (!"READY_TO_PUBLISH".equals(task.status()) || task.candidateId() == null) {
            // 候选仍在造、未裁定或正在核实，都不能提交新切换。
            throw new BizIllegalException(ErrorCode.KNOWLEDGE_NOT_READY, "当前任务尚未通过全部发布验证");
        }
        var scope = candidates.scope(source.id(), task.documentId());
        var candidate = builds.candidate(source.id(), task.candidateId());
        if (scope == null
                || candidate == null
                || !Objects.equals(scope.activeBindingId(), candidate.getBaseBindingId())) {
            throw staleBase();
        }
        var previous = linked(source.id(), scope.activeBindingId(), scope.id());
        if (scope.activeBindingId() != null && (previous == null || previous.verifiedAt() == null)) {
            // 实际入口结果待核实时保留上一笔操作，先核实再继续切换。
            throw new BizIllegalException(ErrorCode.KNOWLEDGE_NOT_READY, "当前生效入口仍待核实");
        }
        var build = candidate.getCurrentBuildId() == null ? null : builds.get(candidate.getCurrentBuildId());
        validations.requirePublishable(source, build, candidate);
        var proof = new Basis(
                scope.id(),
                candidate.getId(),
                scope.activeBindingId(),
                source.configurationId(),
                candidate.getDocumentRevisionId(),
                candidate.getEmbeddingRevisionId(),
                build.id(),
                candidate.getCurrentValidationId(),
                candidate.getFingerprint());
        int revision =
                documents.revision(task.documentId(), task.documentRevisionId()).revision();
        return new Checked(proof, build, candidate.getTitle(), revision, previous == null ? null : previous.revision());
    }

    /**
     * 同版索引核验复用构建保存的真实输出。
     */
    private int verifyIndex(Checked checked) {
        var proof = checked.basis();
        return indexes.verify(
                checked.build(), proof.documentRevision(), proof.embeddingRevision(), proof.fingerprint());
    }

    /**
     * 发布人应能复核实际受控来源，权限和输出限制按当前配置。
     */
    private void requirePublisher(long source) {
        if (!canPublish(source)) {
            throw BizIllegalException.denied();
        }
    }

    /**
     * 核实索引需读取候选正文，与确认发布采用同一当前来源和内容权限。
     */
    private boolean canPublish(long source) {
        return permissions.has(OPERATION)
                && permissions.has("releases.view")
                && permissions.has("knowledge.view")
                && hasSource(source);
    }

    /**
     * 复用既有输出授权，未解析的限制一律不扩张披露。
     */
    private boolean hasSource(long source) {
        return access.findSource(UserContext.getUser(), source)
                .map(scope -> settings.allowsValidationRules(source, scope.getRules()))
                .orElse(false);
    }

    /**
     * 展示绑定的冻结元数据，并按当前来源状态标识实际可用性。
     */
    private Version view(Source source, Binding row) {
        if (row == null) {
            return null;
        }
        var refs = documents.references(row.documentRevisionId());
        var objects = refs.stream()
                .filter(ref -> "TABLE".equals(ref.kind()))
                .map(ref -> ref.label().replaceFirst(" · 表说明$", ""))
                .distinct()
                .toList();
        String availability;
        if (!Objects.equals(row.activeBindingId(), row.id())) {
            availability = "SUPERSEDED";
        } else if (row.verifiedAt() == null) {
            availability = "UNVERIFIED";
        } else if (!"ENABLED".equals(source.lifecycle())) {
            availability = "SOURCE_UNAVAILABLE";
        } else {
            // 原版本的治理限制实时复核，不能借生效状态解除暂停或禁止。
            availability = usable(source, row.embeddingRevisionId(), refs);
        }
        return new Version(
                Ids.text(row.id()),
                Ids.text(row.scopeId()),
                Ids.text(row.taskId()),
                Ids.text(row.documentId()),
                Ids.text(row.documentRevisionId()),
                row.revision(),
                row.title(),
                Ids.text(row.candidateId()),
                row.buildId(),
                row.validationId(),
                Ids.text(row.embeddingRevisionId()),
                row.collectionName(),
                Ids.text(row.publishedBy()),
                Times.instant(row.createdAt()),
                Times.instant(row.verifiedAt()),
                objects,
                availability);
    }

    /**
     * 按权威治理服务检查当前引用可用性。
     */
    private String usable(Source source, long embeddingRevision, List<KnowledgeDocumentMapper.Reference> refs) {
        if (source.scopePending() || source.snapshotId() == null) {
            return "REVIEW_REQUIRED";
        }
        try {
            // 只核对绑定固定的修订，节点选择其他配置不替代原来匹配的模型。
            models.verifyActive(embeddingRevision);
        } catch (BizIllegalException unavailable) {
            return "MODEL_UNAVAILABLE";
        }
        try {
            references.verify(source, refs);
            return "AVAILABLE";
        } catch (BizIllegalException stale) {
            // 已禁止或结构过期时不宣称可继续使用。
            return "REVIEW_REQUIRED";
        }
    }

    /**
     * 确切正文来自原绑定修订，不读取当前草稿。
     */
    private Content content(Binding binding) {
        return json.read(
                documents
                        .revision(binding.documentId(), binding.documentRevisionId())
                        .content(),
                Content.class);
    }

    /**
     * 生效和上一版指针必须在同一来源与范围内。
     */
    private Binding linked(long source, Long id, long scope) {
        var row = id == null ? null : bindings.get(source, id);
        return row != null && row.scopeId() == scope ? row : null;
    }

    /**
     * 原任务编号不能跨来源读写。
     */
    private Task task(long source, long id) {
        var row = tasks.get(source, id);
        if (row == null) {
            throw BizIllegalException.missing();
        }
        return row;
    }

    /**
     * 保证用户确认的是实际看的任务版本。
     */
    private void version(Task task, int expected) {
        if (task.version() != expected) {
            throw BizIllegalException.conflict();
        }
    }

    /**
     * 旧基础失效时提供明确可操作的阻断原因。
     */
    private BizIllegalException staleBase() {
        return new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "基础生效版本已变化，请重新核对并构建新候选");
    }

    /**
     * 审计只包含真实版本与动作事实。
     */
    private void audit(String command, long binding, String name, ChangeVO change) {
        audit.record(AuditWriteDTO.builder()
                .actionCode(OPERATION)
                .targetType("knowledge_release_binding")
                .targetId(Ids.text(binding))
                .targetName(name)
                .commandId(command)
                .changes(List.of(change))
                .securityVersion(commands.auditSecurityVersion(false))
                .build());
    }

    /**
     * 统一命令和核对凭据的原任务目标。
     */
    private String key(long source, long id) {
        return "knowledge-publish:" + source + "/" + id;
    }

    /**
     * 发布核对精确绑定的当前输入，任一值变化都需重新核对。
     */
    private record Basis(
            long scope,
            long candidate,
            Long baseBinding,
            long configuration,
            long documentRevision,
            long embeddingRevision,
            String build,
            String run,
            String fingerprint) {}

    /**
     * 核对的可读信息及实际构建代次。
     */
    private record Checked(Basis basis, Build build, String title, int revision, Integer beforeRevision) {}

    /**
     * 原核实命令绑定页面已读取的聚合版本。
     */
    private record RecheckRequest(int expectedVersion) {}

    /**
     * 同一事务登记的切换事实，只传稳定业务身份。
     */
    private record SwitchEvent(
            String sourceId,
            String scopeId,
            String bindingId,
            String previousBindingId,
            String documentRevisionId,
            String buildId,
            String embeddingRevisionId) {}
}
