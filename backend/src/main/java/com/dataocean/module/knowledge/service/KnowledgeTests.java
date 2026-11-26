package com.dataocean.module.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.knowledge.entity.dto.KnowledgeRevisionConfirmDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeTestDraftDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeTestUpdateDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeDocumentVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeTestVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeTestVO.Content;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper.Reference;
import com.dataocean.module.knowledge.mapper.KnowledgeTestMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeTestMapper.Test;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 标准题从真实文档准备独立期望及审核事实。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeTests {

    /**
     * 题目和不可变修订。
     */
    private final KnowledgeTestMapper tests;

    /**
     * 验收题关联已保存文档。
     */
    private final KnowledgeDocumentMapper documents;

    /**
     * 源锁与 C 批一致，用于确认之后的变化检查。
     */
    private final GovernanceCatalog catalog;

    /**
     * 检查文档范围中的真实确认仍可使用。
     */
    private final KnowledgeReferences references;

    /**
     * 审核复用实际执行的去重计数条件检查。
     */
    private final KnowledgeValidationQuery countQueries;

    /**
     * 共用权限、命令和审计入口。
     */
    private final PermissionService permissions;

    private final MutationService commands;
    private final AuditService audit;

    /**
     * 类型化题目正文。
     */
    private final JsonCodec json;

    /**
     * 读取本源题目，文档定位不扩展来源。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<KnowledgeTestVO.Row> list(long source, Long document, PageQuery query) {
        catalog.source(source, false);
        return PageVO.from(tests.list(query.toFixedOrderMpPage(), source, document), this::row);
    }

    /**
     * 对题单提供基本文档身份，正文仍由文档页面独立核权。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<KnowledgeTestVO.DocumentOption> documentOptions(long source, PageQuery query) {
        catalog.source(source, false);
        return PageVO.from(documents.confirmed(query.toFixedOrderMpPage(), source), row -> {
            var confirmed = documents.revision(row.id(), row.confirmedRevisionId());
            var content = json.read(confirmed.content(), KnowledgeDocumentVO.Content.class);
            return new KnowledgeTestVO.DocumentOption(
                    Long.toString(row.id()), content.getTitle(), Long.toString(row.confirmedRevisionId()));
        });
    }

    /**
     * 题单只选择已确认正文中的引用身份。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<KnowledgeTestVO.FactOption> factOptions(long source, long id, long revision) {
        var current = documents.get(source, id);
        if (current == null || !Objects.equals(current.confirmedRevisionId(), revision)) {
            throw BizIllegalException.missing();
        }
        return documents.references(revision).stream()
                .map(ref -> new KnowledgeTestVO.FactOption(Long.toString(ref.targetId()), ref.kind(), ref.label()))
                .distinct()
                .toList();
    }

    /**
     * 同源读回当前题干和独立期望。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public KnowledgeTestVO detail(long source, long id) {
        var current = test(source, id);
        return new KnowledgeTestVO(row(current), json.read(current.content(), Content.class));
    }

    /**
     * 编辑者先固定准备时所读的文档版本，保存允许部分期望。
     */
    public MutationVO create(long source, String command, KnowledgeTestDraftDTO input) {
        return commands.execute("test-cases.edit", command, target(source, null), input, null, false, false, () -> {
            writable(source);
            return createDraft(source, command, input);
        });
    }

    /**
     * 经核权和同源锁定的事务中，人工新增与采纳共用题目首份修订。
     */
    MutationVO createDraft(long source, String command, KnowledgeTestDraftDTO input) {
        long document = Ids.parse(input.getDocumentId());
        long basis = Ids.parse(input.getDocumentRevisionId());
        verifyDocument(source, document, basis);
        var body = content(input, basis);
        long id = IdWorker.getId();
        long revision = IdWorker.getId();
        tests.create(id, source, document, body.getQuestion(), revision);
        tests.append(revision, id, basis, 1, json.write(body), UserContext.getUser());
        return record(command, "test-cases.edit", id, body.getQuestion(), 1);
    }

    /**
     * 更新不能改换归属文档，相同题目复用实际修订。
     */
    public MutationVO draft(long source, long id, String command, KnowledgeTestUpdateDTO input) {
        int expected = input.getExpectedVersion();
        return commands.execute("test-cases.edit", command, target(source, id), input, expected, false, false, () -> {
            writable(source);
            var current = test(source, id);
            if (current.version() != expected || current.documentId() != Ids.parse(input.getDocumentId())) {
                throw BizIllegalException.conflict();
            }
            long basis = Ids.parse(input.getDocumentRevisionId());
            verifyDocument(source, current.documentId(), basis);
            var body = content(input, basis);
            if (body.equals(json.read(current.content(), Content.class))) {
                return result(command, id, expected);
            }
            long revision = IdWorker.getId();
            tests.append(revision, id, basis, current.number() + 1, json.write(body), UserContext.getUser());
            if (tests.advance(source, id, expected, body.getQuestion(), revision) != 1) {
                throw BizIllegalException.conflict();
            }
            return record(command, "test-cases.edit", id, body.getQuestion(), expected + 1);
        });
    }

    /**
     * 只审核当前已保存题，文档确认和可用的证据都必须保持一致。
     */
    public MutationVO review(long source, long id, String command, KnowledgeRevisionConfirmDTO input) {
        int expected = input.getExpectedVersion();
        return commands.execute("test-cases.review", command, target(source, id), input, expected, false, false, () -> {
            var origin = writable(source);
            var current = test(source, id);
            long revision = Ids.parse(input.getRevisionId());
            if (current.version() != expected || current.headRevisionId() != revision) {
                throw BizIllegalException.conflict();
            }
            if (!Objects.equals(current.confirmedRevisionId(), current.documentRevisionId())) {
                throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "文档的确认修订已变化，请先核对题目依据");
            }
            var facts = documents.references(current.documentRevisionId());
            references.verify(origin, facts);
            var body = json.read(current.content(), Content.class);
            ensureReady(origin, body, facts);
            if (Objects.equals(current.reviewedRevisionId(), revision)) {
                return result(command, id, expected);
            }
            if (tests.review(source, id, expected, revision) != 1) {
                throw BizIllegalException.conflict();
            }
            tests.recordReview(id, revision, UserContext.getUser());
            return record(command, "test-cases.review", id, body.getQuestion(), expected + 1);
        });
    }

    /**
     * 首轮只接受确有预期的数值题或可按依据裁定的说明题。
     */
    private void ensureReady(GovernanceCatalog.Source source, Content body, List<Reference> facts) {
        if (body.getEvidence().isBlank()) {
            throw BizIllegalException.invalid("请填写预期和判定的数据依据");
        }
        if ("EXPLANATION".equals(body.getMode())) {
            if (body.getExpectedExplanation().isBlank()) {
                throw BizIllegalException.invalid("请填写说明题的判定要点");
            }
            return;
        }
        var rule = body.getCountRule();
        if (body.getExpectedCount() == null
                || rule == null
                || value(rule.getMetricId()).isBlank()
                || value(rule.getRelationId()).isBlank()
                || value(rule.getCategoryId()).isBlank()
                || value(rule.getCategoryValue()).isBlank()) {
            throw BizIllegalException.invalid("请填写期望计数以及指标、关联和分类条件");
        }
        countQueries.verify(source, facts, rule);
    }

    /**
     * 关闭失效来源的写入口，当前角色继续复核页面查看权。
     */
    private GovernanceCatalog.Source writable(long source) {
        permissions.require("test-cases.view");
        var origin = catalog.source(source, true);
        if (!"ENABLED".equals(origin.lifecycle()) || origin.scopePending()) {
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "当前来源需要先核对最新目录");
        }
        return origin;
    }

    /**
     * 题单固定一个实际存在、归属正确的正文修订。
     */
    private void verifyDocument(long source, long id, long revision) {
        if (documents.get(source, id) == null || documents.revision(id, revision) == null) {
            throw BizIllegalException.missing();
        }
    }

    /**
     * 当前题目身份只能在同源路径取得。
     */
    private Test test(long source, long id) {
        var current = tests.get(source, id);
        if (current == null) {
            throw BizIllegalException.missing();
        }
        return current;
    }

    /**
     * 草稿和当前审核各自跟踪实际修订。
     */
    private KnowledgeTestVO.Row row(Test current) {
        String status = "DRAFT";
        if (Objects.equals(current.reviewedRevisionId(), current.headRevisionId())) {
            status =
                    Objects.equals(current.confirmedRevisionId(), current.documentRevisionId()) ? "REVIEWED" : "REVIEW";
        }
        return new KnowledgeTestVO.Row(
                Long.toString(current.id()),
                Long.toString(current.documentId()),
                current.question(),
                current.version(),
                Long.toString(current.headRevisionId()),
                status);
    }

    /**
     * 规范可选文本，明确分开两种判断类型。
     */
    private Content content(KnowledgeTestDraftDTO input, long revision) {
        boolean numeric = "NUMERIC".equals(input.getMode());
        return new Content(
                input.getMode(),
                input.getCoverage(),
                input.getQuestion().strip(),
                numeric ? input.getExpectedCount() : null,
                numeric ? "" : value(input.getExpectedExplanation()),
                value(input.getEvidence()),
                numeric ? input.getCountRule() : null,
                Long.toString(revision));
    }

    /**
     * 统一可选内容的空值含义。
     */
    private String value(String text) {
        return text == null ? "" : text.strip();
    }

    /**
     * 以稳定来源和题目身份关联公共命令。
     */
    private String target(long source, Long id) {
        return "knowledge-test:" + source + "/" + (id == null ? "new" : id);
    }

    /**
     * 实际变更与审计同时提交。
     */
    private MutationVO record(String command, String action, long id, String question, int version) {
        audit.record(AuditWriteDTO.builder()
                .actionCode(action)
                .targetType("knowledge_test_case")
                .targetId(Long.toString(id))
                .targetName(question.substring(0, Math.min(question.length(), 120)))
                .securityVersion(commands.auditSecurityVersion(false))
                .commandId(UUID.fromString(command).toString())
                .build());
        return result(command, id, version);
    }

    /**
     * 审核和保存共用真实命令回执。
     */
    private MutationVO result(String command, long id, int version) {
        return new MutationVO(command, "knowledge_test_case", Long.toString(id), version);
    }
}
