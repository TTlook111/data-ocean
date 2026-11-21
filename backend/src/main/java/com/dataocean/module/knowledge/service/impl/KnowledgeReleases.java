package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.knowledge.entity.dto.KnowledgeReleasePrepareDTO;
import com.dataocean.module.knowledge.entity.po.KnowledgeReleaseCandidatePO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeDocumentVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeReleaseCandidateVO;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeReleaseCandidateMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeReleaseCandidateMapper.Scope;
import com.dataocean.module.knowledge.service.KnowledgeReleasePreflight;
import com.dataocean.module.knowledge.service.KnowledgeReleaseService;

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
 * 在来源锁及同一业务事务中保存候选固定输入。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeReleases implements KnowledgeReleaseService {

    /**
     * 创建候选沿用发布办理的构建权限。
     */
    private static final String PREPARE = "releases.build";

    /**
     * 与治理、目录、文档和题单写操作共用来源锁。
     */
    private final GovernanceCatalog catalog;

    /**
     * 预检和固定候选使用同一就绪规则。
     */
    private final KnowledgeReleasePreflight preflight;

    /**
     * 读取确切确认正文的业务标题。
     */
    private final KnowledgeDocumentMapper documents;

    /**
     * 候选、单文档范围和审核题固定关系。
     */
    private final KnowledgeReleaseCandidateMapper candidates;

    /**
     * 命令重试、权限和业务写共享事务。
     */
    private final MutationService commands;

    /**
     * 执行前重新确认当前页面资格。
     */
    private final PermissionService permissions;

    /**
     * 记录实际完成的候选准备动作。
     */
    private final AuditService audit;

    /**
     * 按正式正文类型读取固定标题。
     */
    private final JsonCodec json;

    /**
     * 生成后续复核可重算的固定输入指纹。
     */
    private final Fingerprints fingerprints;

    /**
     * 使用调用方看到的确认修订，重读当前依据后原子落库。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO prepare(long source, String command, KnowledgeReleasePrepareDTO input) {
        long documentId = Ids.parse(input.getDocumentId());
        long revisionId = Ids.parse(input.getDocumentRevisionId());
        String target = "knowledge-release:" + source + "/" + documentId;

        return commands.execute(PREPARE, command, target, input, null, false, false, () -> {
            // 当前读在来源锁之后生效，避免命令核权前形成的快照读到已被替换的定义。
            permissions.require("releases.view");
            var origin = catalog.source(source, true);
            var document = preflight.document(source, documentId);
            if (!Objects.equals(document.confirmedRevisionId(), revisionId)) {
                // 新确认不能替换调用方选择的正文及其题单。
                throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "文档确认修订已变化，请重新核对");
            }
            var checked = preflight.check(origin, document);
            if (!checked.issues().isEmpty()) {
                // 依据失效与缺少构建前置分别反馈，保持已有版本及候选不变。
                var code = checked.stale() ? ErrorCode.DEPENDENCY_STALE : ErrorCode.KNOWLEDGE_NOT_READY;
                throw new BizIllegalException(code, String.join("；", checked.issues()));
            }
            if (!Objects.equals(input.getInputFingerprint(), checked.inputFingerprint())) {
                // 预检之后换了审核题、向量配置或基础绑定，需要用户重新核对。
                throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "发布依据已变化，请重新核对后创建候选");
            }

            var scope = candidates.scope(source, documentId);
            if (scope == null) {
                // 来源锁串行登记首次使用的单文档发布范围。
                scope = new Scope(IdWorker.getId(), null);
                candidates.createScope(scope.id(), source, documentId);
            }
            var confirmed = documents.revision(documentId, revisionId);
            var body = json.read(confirmed.content(), KnowledgeDocumentVO.Content.class);
            var frozen = new Frozen(
                    scope.id(),
                    scope.activeBindingId(),
                    revisionId,
                    checked.embeddingRevision(),
                    checked.testRevisions());

            var row = new KnowledgeReleaseCandidatePO();
            row.setId(IdWorker.getId());
            row.setScopeId(scope.id());
            row.setBaseBindingId(scope.activeBindingId());
            row.setDocumentRevisionId(revisionId);
            row.setEmbeddingRevisionId(checked.embeddingRevision());
            row.setTitle(body.getTitle());
            // 内容指纹独立于命令签名密钥，密钥轮换不改变既有候选的固定输入。
            row.setFingerprint(DigestUtil.sha256Hex(fingerprints.canonical(frozen, false)));
            row.setCreatedBy(UserContext.getUser());
            candidates.insert(row);
            for (long testRevision : checked.testRevisions()) {
                // 审核修订附带原始验证身份，题目之后编辑不替换本次依赖。
                candidates.attachTest(row.getId(), testRevision);
            }

            audit.record(AuditWriteDTO.builder()
                    .actionCode(PREPARE)
                    .targetType("knowledge_release")
                    .targetId(Long.toString(row.getId()))
                    .targetName(row.getTitle())
                    .securityVersion(commands.auditSecurityVersion(false))
                    .commandId(UUID.fromString(command).toString())
                    .build());
            return new MutationVO(command, "knowledge_release", Long.toString(row.getId()), 1);
        });
    }

    /**
     * 同源文档查询和候选分页共享读取快照。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<KnowledgeReleaseCandidateVO> list(
            long source, Long document, String keyword, String status, PageQuery page) {
        catalog.source(source, false);
        if (document != null) {
            // 指定文档时先核实归属，未知或跨源标识不给出空查询的假象。
            preflight.document(source, document);
        }
        if (status != null
                && !Set.of("PREPARED", "BUILDING", "BUILD_FAILED", "READY_FOR_VALIDATION", "CANCELED")
                        .contains(status)) {
            throw BizIllegalException.invalid("请选择有效的发布阶段");
        }
        String term = keyword == null ? "" : keyword.strip();
        return PageVO.from(
                candidates.list(page.toFixedOrderMpPage(), source, document, term, status), KnowledgeReleases::view);
    }

    /**
     * 回读只提供发布页面所需固定标识，不返回配置密钥或题干。
     */
    static KnowledgeReleaseCandidateVO view(KnowledgeReleaseCandidatePO row) {
        return new KnowledgeReleaseCandidateVO(
                Ids.text(row.getId()),
                Ids.text(row.getScopeId()),
                Ids.text(row.getDocumentId()),
                Ids.text(row.getBaseBindingId()),
                Ids.text(row.getDocumentRevisionId()),
                Ids.text(row.getEmbeddingRevisionId()),
                row.getTitle(),
                row.getFingerprint(),
                row.getStatus(),
                row.getVersion(),
                Times.instant(row.getCreatedAt()));
    }

    /**
     * 本次完整候选输入，文档修订已包含不可变章节治理引用。
     */
    private record Frozen(
            long scopeId,
            Long baseBindingId,
            long documentRevisionId,
            long embeddingRevisionId,
            List<Long> reviewedTestRevisions) {}
}
