package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.entity.KnowledgeReviewTask;
import com.dataocean.module.knowledge.enums.DocStatus;
import com.dataocean.module.knowledge.enums.ReviewStatus;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeReviewTaskMapper;
import com.dataocean.module.knowledge.service.VectorIndexTaskService;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识文档生命周期管理服务。
 * <p>
 * 负责文档的状态流转：提交审核、审核通过、审核拒绝、发布。
 * 从 KnowledgeDocServiceImpl 拆分而来，职责单一。
 * </p>
 *
 * @author DataOcean
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocLifecycleService {

    private final KnowledgeDocMapper knowledgeDocMapper;
    private final KnowledgeDocVersionMapper knowledgeDocVersionMapper;
    private final KnowledgeReviewTaskMapper knowledgeReviewTaskMapper;
    private final VectorIndexTaskService vectorIndexTaskService;
    private final DbColumnMetaMapper dbColumnMetaMapper;
    private final KnowledgeDocHelper helper;

    /**
     * 提交审核（DRAFT → PENDING_REVIEW）。
     *
     * @param id 文档 ID
     */
    @Transactional
    public void submitReview(Long id) {
        log.info("提交文档审核 docId={}", id);
        KnowledgeDoc doc = helper.requireDoc(id);
        // 校验当前状态必须为草稿
        if (!DocStatus.DRAFT.name().equals(doc.getStatus())) {
            throw new BusinessException("只有草稿状态的文档才能提交审核");
        }
        doc.setStatus(DocStatus.PENDING_REVIEW.name());
        doc.setUpdatedBy(UserContext.currentUserId());
        knowledgeDocMapper.updateById(doc);
        log.info("文档已提交审核 docId={}", id);
    }

    /**
     * 审核通过（PENDING_REVIEW → APPROVED）。
     *
     * @param id      文档 ID
     * @param comment 审核意见
     */
    @Transactional
    public void approve(Long id, String comment) {
        log.info("审核通过文档 docId={}", id);
        KnowledgeDoc doc = helper.requireDoc(id);
        // 校验当前状态必须为待审核
        if (!DocStatus.PENDING_REVIEW.name().equals(doc.getStatus())) {
            throw new BusinessException("只有待审核状态的文档才能审核");
        }
        // 更新文档状态为审核通过
        doc.setStatus(DocStatus.APPROVED.name());
        doc.setReviewStatus(ReviewStatus.APPROVED.name());
        doc.setUpdatedBy(UserContext.currentUserId());
        knowledgeDocMapper.updateById(doc);
        // 同步写入版本行，使 knowledge_doc_version 的审核状态成为真实数据
        markCurrentVersionReviewed(doc, ReviewStatus.APPROVED.name());
        // 创建审核任务记录
        createReviewTask(doc, ReviewStatus.APPROVED.name(), comment);
        log.info("文档审核通过 docId={}", id);
    }

    /**
     * 审核拒绝（PENDING_REVIEW → DRAFT）。
     *
     * @param id      文档 ID
     * @param comment 拒绝原因
     */
    @Transactional
    public void reject(Long id, String comment) {
        log.info("审核拒绝文档 docId={}", id);
        KnowledgeDoc doc = helper.requireDoc(id);
        // 校验当前状态必须为待审核
        if (!DocStatus.PENDING_REVIEW.name().equals(doc.getStatus())) {
            throw new BusinessException("只有待审核状态的文档才能审核");
        }
        // 更新文档状态回退为草稿
        doc.setStatus(DocStatus.DRAFT.name());
        doc.setReviewStatus(ReviewStatus.REJECTED.name());
        doc.setUpdatedBy(UserContext.currentUserId());
        knowledgeDocMapper.updateById(doc);
        // 同步写入版本行，使 knowledge_doc_version 的审核状态成为真实数据
        markCurrentVersionReviewed(doc, ReviewStatus.REJECTED.name());
        // 创建审核任务记录
        createReviewTask(doc, ReviewStatus.REJECTED.name(), comment);
        log.info("文档审核拒绝 docId={}", id);
    }

    /**
     * 发布文档（APPROVED → INDEXING）。
     * <p>
     * 发布流程：
     * 1. 校验状态为 APPROVED
     * 2. 发布前校验引用字段的治理状态
     * 3. 更新为 INDEXING
     * 4. 创建向量化任务
     * </p>
     *
     * @param id 文档 ID
     */
    @Transactional
    public void publish(Long id) {
        log.info("发布知识文档 docId={}", id);
        KnowledgeDoc doc = helper.requireDoc(id);
        Integer previousPublishedVersionNo = helper.findCurrentPublishedVersionNo(doc.getId());
        boolean rebuildCurrentVersion = previousPublishedVersionNo != null
                && previousPublishedVersionNo.equals(doc.getCurrentVersion());
        // 校验当前状态必须为审核通过
        if (!DocStatus.APPROVED.name().equals(doc.getStatus())) {
            throw new BusinessException("只有审核通过的文档才能发布");
        }
        // 发布前校验：检查引用字段的治理状态
        validateBeforePublish(doc);
        // 更新文档状态为索引中
        KnowledgeDocVersion currentVersion = helper.requireVersion(doc.getId(), doc.getCurrentVersion());
        doc.setStatus(DocStatus.INDEXING.name());
        doc.setUpdatedBy(UserContext.currentUserId());
        knowledgeDocMapper.updateById(doc);
        // 创建带版本上下文的向量化任务；新版本写入成功后再清理旧版本向量。
        vectorIndexTaskService.createTask(
                doc.getDatasourceId(),
                "DOC",
                doc.getId(),
                currentVersion.getMetadataSnapshotId(),
                doc.getCurrentVersion(),
                rebuildCurrentVersion ? doc.getCurrentVersion() : previousPublishedVersionNo);
        log.info("知识文档发布成功 docId={}", id);
    }

    /**
     * 发布前校验：检查文档引用的表字段治理状态。
     * <p>
     * 查询该数据源下所有字段的治理状态，如果存在 DEPRECATED 或 BLOCKED 状态的字段
     * 被文档内容引用（字段名出现在文档中），则阻止发布。
     * </p>
     *
     * @param doc 文档实体
     * @throws BusinessException 存在不合规引用时抛出
     */
    private void validateBeforePublish(KnowledgeDoc doc) {
        String content = doc.getContent();
        if (!StringUtils.hasText(content)) {
            throw new BusinessException("文档内容为空，无法发布到 RAG");
        }
        // 查询该数据源下所有治理状态为 DEPRECATED 或 BLOCKED 的字段
        List<DbColumnMeta> blockedColumns = dbColumnMetaMapper.selectList(
                new LambdaQueryWrapper<DbColumnMeta>()
                        .eq(DbColumnMeta::getDatasourceId, doc.getDatasourceId())
                        .in(DbColumnMeta::getGovernanceStatus, List.of("DEPRECATED", "BLOCKED")));

        // 检查文档内容中是否引用了这些字段
        List<String> violations = new ArrayList<>();
        for (DbColumnMeta col : blockedColumns) {
            if (content.contains(col.getColumnName())) {
                violations.add(col.getTableName() + "." + col.getColumnName()
                        + "（状态：" + col.getGovernanceStatus() + "）");
            }
        }
        if (!violations.isEmpty()) {
            throw new BusinessException("发布失败：文档引用了已废弃或已阻断的字段 — " + String.join("、", violations));
        }
    }

    /**
     * 把审核结果写回当前版本行。
     * <p>
     * 知识文档的审核作用于「当前版本」，因此审核状态与审核人应落在
     * `knowledge_doc_version` 的对应行上。该列此前从未被写入（恒为建表默认值
     * `PENDING`），导致任何按版本展示审核状态的地方都会得到「恒为待审核」的错误结论
     * ——包括已发布的版本。V51 迁移已把历史行回填为 `UNKNOWN` 并修正列注释。
     * </p>
     *
     * @param doc          文档实体
     * @param reviewStatus 审核状态
     */
    private void markCurrentVersionReviewed(KnowledgeDoc doc, String reviewStatus) {
        KnowledgeDocVersion currentVersion = findCurrentVersion(doc);
        if (currentVersion == null) {
            // 没有版本行时不阻断审核本身：文档主表的状态已经更新，版本行缺失属数据异常。
            log.warn("文档缺少当前版本行，跳过版本审核状态写入 docId={} versionNo={}",
                    doc.getId(), doc.getCurrentVersion());
            return;
        }
        currentVersion.setReviewStatus(reviewStatus);
        currentVersion.setReviewerId(UserContext.currentUserId());
        knowledgeDocVersionMapper.updateById(currentVersion);
    }

    /**
     * 查询文档的当前版本行。
     *
     * @param doc 文档实体
     * @return 当前版本行；不存在时返回 null
     */
    private KnowledgeDocVersion findCurrentVersion(KnowledgeDoc doc) {
        return knowledgeDocVersionMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocVersion>()
                        .eq(KnowledgeDocVersion::getDocId, doc.getId())
                        .eq(KnowledgeDocVersion::getVersionNo, doc.getCurrentVersion()));
    }

    /**
     * 创建审核任务记录。
     *
     * @param doc          文档实体
     * @param reviewStatus 审核状态
     * @param comment      审核意见
     */
    private void createReviewTask(KnowledgeDoc doc, String reviewStatus, String comment) {
        // 查询当前版本记录 ID
        KnowledgeDocVersion currentVersion = findCurrentVersion(doc);
        Long docVersionId = currentVersion != null ? currentVersion.getId() : null;

        KnowledgeReviewTask reviewTask = KnowledgeReviewTask.builder()
                .docVersionId(docVersionId)
                .reviewerId(UserContext.currentUserId())
                .reviewStatus(reviewStatus)
                .reviewComment(comment)
                .submittedAt(LocalDateTime.now())
                .reviewedAt(LocalDateTime.now())
                .build();
        knowledgeReviewTaskMapper.insert(reviewTask);
    }
}
