package com.dataocean.module.knowledge.service;

import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.knowledge.entity.vo.KnowledgeDocumentVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeReleasePreflightVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeTestVO;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeTestMapper;
import com.dataocean.module.model.service.ModelRegistry;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

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
     * 仅提供当前源的已确认文档选项。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<KnowledgeTestVO.DocumentOption> options(long source, PageQuery page) {
        catalog.source(source, false);
        return PageVO.from(documents.confirmed(page.toFixedOrderMpPage(), source), document -> {
            var revision = documents.revision(document.id(), document.confirmedRevisionId());
            var body = json.read(revision.content(), KnowledgeDocumentVO.Content.class);
            return new KnowledgeTestVO.DocumentOption(
                    Long.toString(document.id()), body.getTitle(), Ids.text(document.confirmedRevisionId()));
        });
    }

    /**
     * 读取已保存事实，列出缺题、依据变化与模型分配问题。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public KnowledgeReleasePreflightVO inspect(long source, long id) {
        var origin = catalog.source(source, false);
        var doc = documents.get(source, id);
        if (doc == null) {
            // 用同源条件解析文档，越界编号不得进入发布准备。
            throw BizIllegalException.missing();
        }
        var issues = new ArrayList<String>();
        boolean sourceReady =
                "ENABLED".equals(origin.lifecycle()) && !origin.scopePending() && origin.snapshotId() != null;
        if (!sourceReady) {
            // 来源已停用或目录尚未核对时，仍展示历史题单，但不能进入候选准备。
            issues.add("当前数据源未启用或没有适用的成功目录");
        }
        List<KnowledgeTestVO.Content> reviewed = List.of();
        if (doc.confirmedRevisionId() == null) {
            // 待确认草稿不能为题单和发布提供正式依据。
            issues.add("请先确认可用于发布的文档修订");
        } else {
            if (sourceReady) {
                try {
                    // 同一读快照内复查已确认文档的固定治理依据。
                    references.verify(origin, documents.references(doc.confirmedRevisionId()));
                } catch (BizIllegalException rejected) {
                    // 仅说明需要复核，治理详情继续依照各自权限读取。
                    issues.add("当前治理依据需要重新核对");
                }
            }
            reviewed = tests.reviewed(source, id, doc.confirmedRevisionId()).stream()
                    .map(body -> json.read(body, KnowledgeTestVO.Content.class))
                    .toList();
            if (reviewed.stream()
                    .noneMatch(test -> "NUMERIC".equals(test.getMode()) && "CAPABILITY".equals(test.getCoverage()))) {
                issues.add("缺少已审核的关键能力数值题");
            }
            if (reviewed.stream()
                    .noneMatch(test -> "EXPLANATION".equals(test.getMode()) && "BOUNDARY".equals(test.getCoverage()))) {
                issues.add("缺少已审核的使用边界说明题");
            }
        }
        String embedding = null;
        try {
            // 只核对当前配置选择，模型调用和取用由后续构建任务执行。
            long revision = models.requireBinding("rag-embedding");
            models.verifyActive(revision);
            embedding = Long.toString(revision);
        } catch (BizIllegalException rejected) {
            issues.add("知识检索向量化节点未分配可用模型");
        }
        return new KnowledgeReleasePreflightVO(
                Long.toString(id), Ids.text(doc.confirmedRevisionId()), reviewed.size(), embedding, issues);
    }
}
