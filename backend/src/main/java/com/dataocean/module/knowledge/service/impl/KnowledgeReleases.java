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
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.knowledge.entity.dto.KnowledgeReleasePrepareDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeReleaseScopeDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeDocumentVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeReleaseTaskVO;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeReleaseCandidateMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeReleaseCandidateMapper.Scope;
import com.dataocean.module.knowledge.mapper.KnowledgeReleaseTaskMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeReleaseTaskMapper.Task;
import com.dataocean.module.knowledge.service.KnowledgeReleasePreflight;
import com.dataocean.module.knowledge.service.KnowledgeReleaseService;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 保存已确认文档的发布准备任务，并回读其当前阶段。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeReleases implements KnowledgeReleaseService {

    /**
     * 创建和调整任务沿用发布办理的构建权限。
     */
    private static final String PREPARE = "releases.build";

    /**
     * 允许调整的静止阶段，执行中的代次不能被替换。
     */
    private static final Set<String> ADJUSTABLE = Set.of(
            "PREPARED",
            "BUILD_FAILED",
            "READY_FOR_VALIDATION",
            "AWAITING_JUDGMENT",
            "VALIDATION_FAILED",
            "VALIDATION_ERROR",
            "READY_TO_PUBLISH");

    /**
     * 与治理、目录、文档和题单写操作共用来源锁。
     */
    private final GovernanceCatalog catalog;

    /**
     * 保存任务范围时校验已确认文档及其治理依据。
     */
    private final KnowledgeReleasePreflight preflight;

    /**
     * 读取确切确认正文的业务标题。
     */
    private final KnowledgeDocumentMapper documents;

    /**
     * 单文档发布范围及当前生效指针。
     */
    private final KnowledgeReleaseCandidateMapper candidates;

    /**
     * 保存和查询发布任务。
     */
    private final KnowledgeReleaseTaskMapper tasks;

    /**
     * 命令重试、权限和业务写共享事务。
     */
    private final MutationService commands;

    /**
     * 执行前重新确认当前页面资格。
     */
    private final PermissionService permissions;

    /**
     * 记录实际完成的任务创建和范围变化。
     */
    private final AuditService audit;

    /**
     * 按正式正文类型读取固定标题。
     */
    private final JsonCodec json;

    /**
     * 使用调用方看到的确认修订，重读当前依据后原子落库。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO prepare(long source, String command, KnowledgeReleasePrepareDTO input) {
        long documentId = Ids.parse(input.getDocumentId());
        long revisionId = Ids.parse(input.getDocumentRevisionId());
        String target = "knowledge-release-task:" + source + "/" + documentId;

        return commands.execute(PREPARE, command, target, input, null, false, false, () -> {
            // 当前读在来源锁之后生效，避免命令核权前形成的快照读到已被替换的定义。
            permissions.require("releases.view");
            var origin = catalog.source(source, true);
            var scope = reviewedScope(origin, documentId, revisionId);
            long id = IdWorker.getId();
            tasks.insert(id, scope.id(), revisionId, scope.title(), UserContext.getUser());

            audit.record(AuditWriteDTO.builder()
                    .actionCode(PREPARE)
                    .targetType("knowledge_release")
                    .targetId(Ids.text(id))
                    .targetName(scope.title())
                    .securityVersion(commands.auditSecurityVersion(false))
                    .commandId(UUID.fromString(command).toString())
                    .build());
            return new MutationVO(command, "knowledge_release", Ids.text(id), 1);
        });
    }

    /**
     * 在来源锁内替换目标并递增原任务版本，不修改旧候选及其构建快照。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO adjustScope(long source, long id, String command, KnowledgeReleaseScopeDTO input) {
        long documentId = Ids.parse(input.getDocumentId());
        long revisionId = Ids.parse(input.getDocumentRevisionId());
        String target = "knowledge-release-scope:" + source + "/" + id;
        return commands.execute(PREPARE, command, target, input, input.getExpectedVersion(), false, false, () -> {
            // 与构建、取消和工作者回执共用来源锁。
            permissions.require("releases.view");
            var origin = catalog.source(source, true);
            var task = tasks.get(source, id);
            if (task == null) {
                // 任务编号必须属于当前操作源。
                throw BizIllegalException.missing();
            }
            if (task.version() != input.getExpectedVersion()) {
                // 旧窗口不能覆盖已经开始的新构建或其他调整。
                throw BizIllegalException.conflict();
            }
            if (!ADJUSTABLE.contains(task.status())) {
                // 运行中和已取消的任务不可替换输入。
                throw BizIllegalException.state("当前发布任务不能调整范围");
            }
            var scope = reviewedScope(origin, documentId, revisionId);
            if (task.documentId() == documentId && task.documentRevisionId() == revisionId) {
                // 不改动候选指针或版本，也不增加变化审计。
                return new MutationVO(command, "knowledge_release", Ids.text(id), task.version());
            }
            int version = task.version() + 1;
            if (tasks.adjust(id, scope.id(), revisionId, scope.title(), version) != 1) {
                // 未能保存时撤销本次事务。
                throw BizIllegalException.conflict();
            }
            var previous = documents.revision(task.documentId(), task.documentRevisionId());
            audit.record(AuditWriteDTO.builder()
                    .actionCode(PREPARE)
                    .targetType("knowledge_release")
                    .targetId(Ids.text(id))
                    .targetName(scope.title())
                    .changes(List.of(new ChangeVO(
                            "scope",
                            "发布范围",
                            task.title() + " · r" + previous.revision(),
                            scope.title() + " · r" + scope.revision())))
                    .securityVersion(commands.auditSecurityVersion(false))
                    .commandId(UUID.fromString(command).toString())
                    .build());
            return new MutationVO(command, "knowledge_release", Ids.text(id), version);
        });
    }

    /**
     * 创建和调整都验证操作者实际核对过的同源确认正文。
     */
    private ReviewedScope reviewedScope(Source origin, long documentId, long revisionId) {
        var document = preflight.document(origin.id(), documentId);
        if (!Objects.equals(document.confirmedRevisionId(), revisionId)) {
            // 新确认不能静默替换操作者选定的修订。
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "文档确认修订已变化，请重新核对");
        }
        var issues = preflight.documentIssues(origin, document);
        if (!issues.isEmpty()) {
            // 题单和模型前置仍在后续构建时办理。
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, String.join("；", issues));
        }
        var scope = candidates.scope(origin.id(), documentId);
        if (scope == null) {
            // 来源锁串行登记首次使用的单文档范围。
            scope = new Scope(IdWorker.getId(), null);
            candidates.createScope(scope.id(), origin.id(), documentId);
        }
        var confirmed = documents.revision(documentId, revisionId);
        var body = json.read(confirmed.content(), KnowledgeDocumentVO.Content.class);
        return new ReviewedScope(scope.id(), body.getTitle(), confirmed.revision());
    }

    /**
     * 范围保存和审计共用的确切确认摘要。
     */
    private record ReviewedScope(long id, String title, int revision) {}

    /**
     * 同源文档查询和任务分页共享读取快照。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<KnowledgeReleaseTaskVO> list(
            long source, Long document, String keyword, String status, PageQuery page) {
        catalog.source(source, false);
        if (document != null) {
            // 指定文档时先核实归属，未知或跨源标识不给出空查询的假象。
            preflight.document(source, document);
        }
        if (status != null
                && !ADJUSTABLE.contains(status)
                && !Set.of("BUILDING", "VALIDATING", "CANCELED", "PUBLISH_RECONCILE", "PUBLISHED")
                        .contains(status)) {
            throw BizIllegalException.invalid("请选择有效的发布阶段");
        }
        String term = keyword == null ? "" : keyword.strip();
        return PageVO.from(
                tasks.list(page.toFixedOrderMpPage(), source, document, term, status), KnowledgeReleases::view);
    }

    /**
     * 投影任务编号和可选候选编号，供列表与详情统一展示。
     */
    static KnowledgeReleaseTaskVO view(Task row) {
        return new KnowledgeReleaseTaskVO(
                Ids.text(row.id()),
                Ids.text(row.candidateId()),
                Ids.text(row.documentId()),
                Ids.text(row.baseBindingId()),
                Ids.text(row.documentRevisionId()),
                row.title(),
                row.fingerprint(),
                row.status(),
                row.version(),
                Times.instant(row.createdAt()));
    }
}
