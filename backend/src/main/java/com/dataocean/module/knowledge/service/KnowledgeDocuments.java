package com.dataocean.module.knowledge.service;

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
import com.dataocean.module.governance.entity.vo.GovernanceFactOptionVO;
import com.dataocean.module.governance.service.GovernanceKnowledgeService;
import com.dataocean.module.governance.service.GovernanceTarget;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.knowledge.entity.dto.KnowledgeDocumentDraftDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeDocumentUpdateDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeRevisionConfirmDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeDocumentVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeDocumentVO.Content;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper.Document;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper.Reference;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 从实际治理确认生成可回看的人工知识稿与确认修订。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeDocuments {

    /**
     * 文档正文、引用和版本。
     */
    private final KnowledgeDocumentMapper documents;

    /**
     * 当前有效目录及与 C 批共用的源锁。
     */
    private final GovernanceCatalog catalog;

    /**
     * 知识选择器使用的治理事实。
     */
    private final GovernanceKnowledgeService governance;

    /**
     * 文档依赖捕获和过期检查。
     */
    private final KnowledgeReferences references;

    /**
     * 文档间显式引用与治理依据分别保存。
     */
    private final KnowledgeDocumentLinks links;

    /**
     * 以共用命令和审计保护重试及当前授权。
     */
    private final MutationService commands;

    /**
     * 在源锁后复核页面能力。
     */
    private final PermissionService permissions;

    /**
     * 审计记录与写入原子提交。
     */
    private final AuditService audit;

    /**
     * 固定正文以类型化结构保存。
     */
    private final JsonCodec json;

    /**
     * 读取当前可选的已确认事实。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<GovernanceFactOptionVO> facts(long source, String kind, Long object, PageQuery query) {
        return governance.list(catalog.source(source, false), kind, object, query);
    }

    /**
     * 按数据源与实际标题分页。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<KnowledgeDocumentVO.Row> list(long source, String keyword, PageQuery query) {
        catalog.source(source, false);
        return PageVO.from(documents.list(query.toFixedOrderMpPage(), source, keyword), row -> row(row, state(row)));
    }

    /**
     * 详情返回一次真实读取看到的正文、依赖与聚合状态。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public KnowledgeDocumentVO detail(long source, long id, Long revision) {
        var origin = catalog.source(source, false);
        var document = document(source, id);
        var saved = documents.revision(id, revision == null ? document.headRevisionId() : revision);
        if (saved == null) {
            throw BizIllegalException.missing();
        }
        var chosen = documents.references(saved.id());
        String state = state(document);
        if ("CONFIRMED".equals(state)) {
            try {
                // 固定历史读取和现行是否还可发布采用不同的事实。
                references.verify(
                        origin,
                        saved.id() == document.headRevisionId()
                                ? chosen
                                : documents.references(document.headRevisionId()));
            } catch (BizIllegalException changed) {
                state = "REVIEW";
            }
        }
        return new KnowledgeDocumentVO(
                row(document, state),
                Long.toString(saved.id()),
                json.read(saved.content(), Content.class),
                chosen.stream().map(this::view).toList(),
                links.documents(saved.id()).stream()
                        .map(link -> new KnowledgeDocumentVO.DocumentLink(
                                Ids.text(link), document(source, link).title()))
                        .toList(),
                documents.history(id).stream()
                        .map(h -> new KnowledgeDocumentVO.Revision(
                                Long.toString(h.id()),
                                h.revision(),
                                Long.toString(h.createdBy()),
                                Times.instant(h.createdAt())))
                        .toList());
    }

    /**
     * 创建草稿使用同源固定引用和稳定命令。
     */
    public MutationVO create(long source, String command, KnowledgeDocumentDraftDTO input) {
        return commands.execute("knowledge.edit", command, target(source, null), input, null, false, false, () -> {
            var origin = writable(source);
            var body = content(input);
            var captured = references.capture(origin, input);
            var required = input.getRequiredDocuments() == null
                    ? List.<Long>of()
                    : links.capture(source, null, input.getRequiredDocuments());
            long id = IdWorker.getId();
            long revision = IdWorker.getId();
            documents.create(id, source, body.getTitle(), revision);
            append(id, revision, 1, body, captured, required);
            return recorded(command, "knowledge.edit", id, body.getTitle(), 1);
        });
    }

    /**
     * 修改产生新正文，完全相同的输入复用现有修订。
     */
    public MutationVO draft(long source, long id, String command, KnowledgeDocumentUpdateDTO input) {
        int expected = input.getExpectedVersion();
        return commands.execute("knowledge.edit", command, target(source, id), input, expected, false, false, () -> {
            var origin = writable(source);
            var current = document(source, id);
            version(current, expected);
            var body = content(input);
            var captured = references.capture(origin, input);
            var prior = documents.revision(id, current.headRevisionId());
            var oldLinks = links.documents(prior.id());
            var required = input.getRequiredDocuments() == null
                    ? oldLinks
                    : links.capture(source, id, input.getRequiredDocuments());
            // MySQL 的 JSON 列会规范化格式，按类型化内容和依赖集合判断变化。
            if (body.equals(json.read(prior.content(), Content.class))
                    && required.equals(oldLinks)
                    && ordered(captured).equals(ordered(documents.references(prior.id())))) {
                return mutation(command, id, expected);
            }
            long revision = IdWorker.getId();
            append(id, revision, prior.revision() + 1, body, captured, required);
            if (documents.advance(source, id, expected, body.getTitle(), revision) != 1) {
                throw BizIllegalException.conflict();
            }
            return recorded(command, "knowledge.edit", id, body.getTitle(), expected + 1);
        });
    }

    /**
     * 确认必须读取当前草稿、实际正文与固定依据。
     */
    public MutationVO confirm(long source, long id, String command, KnowledgeRevisionConfirmDTO input) {
        int expected = input.getExpectedVersion();
        return commands.execute("knowledge.confirm", command, target(source, id), input, expected, false, false, () -> {
            var origin = writable(source);
            var current = document(source, id);
            version(current, expected);
            if (current.headRevisionId() != Ids.parse(input.getRevisionId())) {
                throw BizIllegalException.conflict();
            }
            var saved = documents.revision(id, current.headRevisionId());
            var body = json.read(saved.content(), Content.class);
            var captured = documents.references(saved.id());
            if (body.getPurpose().isBlank()
                    || body.getQuestions().isEmpty()
                    || body.getSections().stream()
                            .anyMatch(section -> section.getBody().isBlank())) {
                throw BizIllegalException.invalid("请填写用途、代表性问题及各节正文");
            }
            for (int index = 0; index < body.getSections().size(); index++) {
                // 每一节实际内容都要能追溯到人工选择的确认记录。
                final int section = index;
                if (captured.stream().noneMatch(ref -> ref.sectionIndex() == section && ref.selected())) {
                    throw BizIllegalException.invalid("每节正文至少选择一项已确认治理依据");
                }
            }
            references.verify(origin, captured);
            if (Objects.equals(current.confirmedRevisionId(), current.headRevisionId())) {
                return mutation(command, id, expected);
            }
            long actor = UserContext.getUser();
            if (documents.confirm(source, id, expected, saved.id(), actor) != 1) {
                throw BizIllegalException.conflict();
            }
            documents.recordConfirmation(id, saved.id(), actor);
            return recorded(command, "knowledge.confirm", id, body.getTitle(), expected + 1);
        });
    }

    /**
     * 核验数据源允许进入当前知识办理。
     */
    private Source writable(long source) {
        permissions.require("knowledge.view");
        var result = catalog.source(source, true);
        if (!"ENABLED".equals(result.lifecycle()) || result.scopePending() || result.snapshotId() == null) {
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "数据源尚无当前有效的成功目录");
        }
        return result;
    }

    /**
     * 查阅或编辑只能定位当前源下的文档。
     */
    private Document document(long source, long id) {
        var current = documents.get(source, id);
        if (current == null) {
            throw BizIllegalException.missing();
        }
        return current;
    }

    /**
     * 编辑和确认统一核对客户端看到的聚合版本。
     */
    private void version(Document current, int expected) {
        if (current.version() != expected) {
            throw BizIllegalException.conflict();
        }
    }

    /**
     * 转换用户输入为实际可保存的不可变正文。
     */
    private Content content(KnowledgeDocumentDraftDTO body) {
        return new Content(
                body.getTitle().strip(),
                value(body.getPurpose()),
                value(body.getExclusions()),
                body.getQuestions() == null
                        ? List.of()
                        : body.getQuestions().stream().map(String::strip).toList(),
                body.getSections().stream()
                        .map(section -> new KnowledgeDocumentVO.Section(
                                section.getTitle().strip(), value(section.getBody())))
                        .toList());
    }

    /**
     * 空的可选输入与空白文本采用一致表示。
     */
    private String value(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 保存不可变正文以及同一事务中固定的每节依据。
     */
    private void append(
            long id, long revision, int number, Content body, List<Reference> captured, List<Long> required) {
        documents.append(revision, id, number, json.write(body), UserContext.getUser());
        captured.forEach(ref -> documents.reference(revision, ref));
        links.save(revision, required);
    }

    /**
     * 判断引用集合是否发生实质变化，排除读取顺序的影响。
     */
    private List<Reference> ordered(List<Reference> refs) {
        return refs.stream()
                .sorted(Comparator.comparingInt(Reference::sectionIndex)
                        .thenComparing(Reference::kind)
                        .thenComparingLong(Reference::targetId))
                .toList();
    }

    /**
     * 当前确认指针与草稿指针独立。
     */
    private String state(Document row) {
        return Objects.equals(row.confirmedRevisionId(), row.headRevisionId()) ? "CONFIRMED" : "DRAFT";
    }

    /**
     * 当前文档摘要用于列表及详情。
     */
    private KnowledgeDocumentVO.Row row(Document row, String state) {
        return new KnowledgeDocumentVO.Row(
                Long.toString(row.id()),
                row.title(),
                row.version(),
                Long.toString(row.headRevisionId()),
                Ids.text(row.confirmedRevisionId()),
                state,
                Ids.text(row.confirmedBy()),
                Times.instant(row.confirmedAt()));
    }

    /**
     * 用稳定治理接口模型承载真实的来源定位。
     */
    private KnowledgeDocumentVO.Reference view(Reference ref) {
        return new KnowledgeDocumentVO.Reference(
                ref.sectionIndex(),
                new GovernanceTarget(ref.sourceId(), ref.objectId(), ref.kind(), ref.targetId()).view(),
                Long.toString(ref.factRevisionId()),
                ref.generation(),
                Long.toString(ref.structureRevisionId()),
                ref.selected(),
                ref.label());
    }

    /**
     * 防重指纹必须绑定稳定源和目标。
     */
    private String target(long source, Long id) {
        return "knowledge-document:" + source + "/" + (id == null ? "new" : id);
    }

    /**
     * 命令记录同一次实际写入的审计。
     */
    private MutationVO recorded(String command, String operation, long id, String title, int version) {
        audit.record(AuditWriteDTO.builder()
                .actionCode(operation)
                .targetType("knowledge_document")
                .targetId(Long.toString(id))
                .targetName(title)
                .securityVersion(commands.auditSecurityVersion(false))
                .commandId(UUID.fromString(command).toString())
                .build());
        return mutation(command, id, version);
    }

    /**
     * 对外复用公共提交回执。
     */
    private MutationVO mutation(String command, long id, int version) {
        return new MutationVO(command, "knowledge_document", Long.toString(id), version);
    }
}
