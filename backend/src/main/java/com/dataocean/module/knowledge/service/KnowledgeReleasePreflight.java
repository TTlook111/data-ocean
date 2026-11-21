package com.dataocean.module.knowledge.service;

import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.knowledge.entity.vo.KnowledgeDocumentVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeReleasePreflightVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeTestVO;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper.Document;
import com.dataocean.module.knowledge.mapper.KnowledgeReleaseCandidateMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeTestMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeTestMapper.ReviewedTest;
import com.dataocean.module.model.service.ModelRegistry;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;

/**
 * 检查首个事项进入构建所需的确切文档、题目和模型配置。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeReleasePreflight {

    /**
     * 当前成功目录只读快照。
     */
    private final GovernanceCatalog catalog;

    /**
     * 文档指针及冻结依赖。
     */
    private final KnowledgeDocumentMapper documents;

    /**
     * 已审核标准题以原修订绑定文档。
     */
    private final KnowledgeTestMapper tests;

    /**
     * 按当前权威治理版本重新核对。
     */
    private final KnowledgeReferences references;

    /**
     * 向量节点来自系统配置。
     */
    private final ModelRegistry models;

    /**
     * 读取类型化题目覆盖点。
     */
    private final JsonCodec json;

    /**
     * 当前单文档范围的实际生效绑定。
     */
    private final KnowledgeReleaseCandidateMapper candidates;

    /**
     * 生成与密钥轮换无关的预检输入摘要。
     */
    private final Fingerprints fingerprints;

    /**
     * 仅提供当前源的已确认文档选项。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<KnowledgeTestVO.DocumentOption> options(long source, String keyword, PageQuery page) {
        catalog.source(source, false);
        String search = keyword == null ? "" : keyword.strip();
        return PageVO.from(documents.releaseOptions(page.toFixedOrderMpPage(), source, search), option -> {
            // 使用确认时的标题，草稿改名不会改变发布选择。
            return new KnowledgeTestVO.DocumentOption(
                    Long.toString(option.documentId()), option.title(), Ids.text(option.revisionId()));
        });
    }

    /**
     * 读取已保存事实，列出缺题、依据变化与模型分配问题。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public KnowledgeReleasePreflightVO inspect(long source, long id) {
        var origin = catalog.source(source, false);
        var document = document(source, id);
        var result = check(origin, document);
        String title = document.title();
        Integer revisionNumber = null;
        if (result.documentRevision() != null) {
            // 直达选项即使不在下拉当前页，也显示其确认名称。
            var revision = documents.revision(id, result.documentRevision());
            revisionNumber = revision.revision();
            title = json.read(revision.content(), KnowledgeDocumentVO.Content.class)
                    .getTitle();
        }
        var scope = candidates.scope(source, id);
        return new KnowledgeReleasePreflightVO(
                Long.toString(id),
                title,
                Ids.text(result.documentRevision()),
                revisionNumber,
                scope == null ? null : Ids.text(scope.activeBindingId()),
                result.documentReady(),
                result.testRevisions().size(),
                Ids.text(result.embeddingRevision()),
                result.inputFingerprint(),
                result.issues());
    }

    /**
     * 在业务事务内定位同源文档，读取和创建均使用相同的资源边界。
     */
    public Document document(long source, long id) {
        var doc = documents.get(source, id);
        if (doc == null) {
            // 用同源条件解析文档，越界编号不得进入发布准备。
            throw BizIllegalException.missing();
        }
        return doc;
    }

    /**
     * 检查文档本身能否作为发布范围，题单与模型另在构建前核对。
     */
    public List<String> documentIssues(Source origin, Document doc) {
        var issues = new ArrayList<String>();
        boolean sourceReady =
                "ENABLED".equals(origin.lifecycle()) && !origin.scopePending() && origin.snapshotId() != null;
        if (!sourceReady) {
            // 停用或待核对的来源无法提供当前文档依据。
            issues.add("当前数据源未启用或没有适用的成功目录");
        }
        if (doc.confirmedRevisionId() == null) {
            // 只接受已确认的正文版本。
            issues.add("请先确认可用于发布的文档修订");
        } else if (sourceReady) {
            try {
                // 依据来自当前目录和文档的固定治理引用。
                references.verify(origin, documents.references(doc.confirmedRevisionId()));
            } catch (BizIllegalException rejected) {
                // 治理细节由具备相应权限的页面提供。
                issues.add("当前治理依据需要重新核对");
            }
        }
        return issues;
    }

    /**
     * 在读快照或来源事务锁内收集同一确认对应的全部构建输入。
     */
    public Inspection check(Source origin, Document doc) {
        // 文档依据与题单问题分别展示，保存任务只看前者。
        var issues = documentIssues(origin, doc);
        boolean documentReady = issues.isEmpty();
        List<ReviewedTest> reviewed = List.of();
        if (doc.confirmedRevisionId() != null) {
            // 只统计适用于同一确认修订的标准题。
            reviewed = tests.reviewed(origin.id(), doc.id(), doc.confirmedRevisionId());
            var content = reviewed.stream()
                    .map(test -> json.read(test.content(), KnowledgeTestVO.Content.class))
                    .toList();
            if (content.stream()
                    .noneMatch(test -> "NUMERIC".equals(test.getMode()) && "CAPABILITY".equals(test.getCoverage()))) {
                issues.add("缺少已审核的关键能力数值题");
            }
            if (content.stream()
                    .noneMatch(test -> "EXPLANATION".equals(test.getMode()) && "BOUNDARY".equals(test.getCoverage()))) {
                issues.add("缺少已审核的使用边界说明题");
            }
        }
        Long embedding = null;
        try {
            // 只核对当前配置选择，模型调用和取用由后续构建任务执行。
            long revision = models.requireBinding("rag-embedding");
            models.verifyActive(revision);
            embedding = revision;
        } catch (BizIllegalException rejected) {
            // 模型问题留在构建预检中，准备任务仍可保存。
            issues.add("知识检索向量化节点未分配可用模型");
        }
        var testRevisions =
                reviewed.stream().map(ReviewedTest::revisionId).sorted().toList();
        String inputFingerprint = null;
        if (issues.isEmpty()) {
            // 对确认、题单、模型和基础绑定生成摘要，构建事务会重算并核对。
            var scope = candidates.scope(origin.id(), doc.id());
            var basis = new Basis(
                    origin.id(),
                    doc.id(),
                    doc.confirmedRevisionId(),
                    testRevisions,
                    embedding,
                    scope == null ? null : scope.activeBindingId());
            inputFingerprint = DigestUtil.sha256Hex(fingerprints.canonical(basis, false));
        }
        return new Inspection(
                doc.confirmedRevisionId(),
                testRevisions,
                embedding,
                List.copyOf(issues),
                documentReady,
                inputFingerprint);
    }

    /**
     * 记录本次查验看到的确切输入，写入候选前不重新读取可变题单。
     */
    public record Inspection(
            Long documentRevision,
            List<Long> testRevisions,
            Long embeddingRevision,
            List<String> issues,
            boolean documentReady,
            String inputFingerprint) {}

    /**
     * 固定可提交的全部依赖。首次登记范围不改变同一基础绑定。
     */
    private record Basis(
            long source,
            long document,
            long documentRevision,
            List<Long> testRevisions,
            long embeddingRevision,
            Long baseBindingId) {}
}
