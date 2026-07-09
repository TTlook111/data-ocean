package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.enums.DocStatus;
import com.dataocean.module.knowledge.enums.GenerationSource;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.support.KnowledgeDependencySnapshotBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 知识文档 CRUD 服务。
 * <p>
 * 负责文档的创建、编辑、查询等基础操作。
 * 从 KnowledgeDocServiceImpl 拆分而来，职责单一。
 * </p>
 *
 * @author DataOcean
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocCrudService {

    private final KnowledgeDocMapper knowledgeDocMapper;
    private final KnowledgeDocVersionMapper knowledgeDocVersionMapper;
    private final KnowledgeDependencySnapshotBuilder dependencySnapshotBuilder;
    private final KnowledgeDocHelper helper;

    /**
     * 分页查询知识文档列表。
     * <p>
     * 支持按数据源 ID 和文档状态筛选，结果按创建时间降序排列。
     * </p>
     *
     * @param datasourceId 数据源 ID（可选筛选）
     * @param status       文档状态（可选筛选）
     * @param page         页码
     * @param pageSize     每页大小
     * @return 分页文档列表
     */
    public Page<KnowledgeDoc> listDocs(Long datasourceId, String status, Integer page, Integer pageSize) {
        log.debug("查询知识文档列表 datasourceId={} status={} page={} pageSize={}", datasourceId, status, page, pageSize);
        // 构建动态查询条件
        LambdaQueryWrapper<KnowledgeDoc> wrapper = new LambdaQueryWrapper<KnowledgeDoc>()
                .eq(datasourceId != null, KnowledgeDoc::getDatasourceId, datasourceId)
                .eq(StringUtils.hasText(status), KnowledgeDoc::getStatus, status)
                .orderByDesc(KnowledgeDoc::getCreatedAt);
        return knowledgeDocMapper.selectPage(new Page<>(page, pageSize), wrapper);
    }

    /**
     * 获取文档详情。
     *
     * @param id 文档 ID
     * @return 文档实体
     */
    public KnowledgeDoc getDocById(Long id) {
        return helper.requireDoc(id);
    }

    /**
     * 创建知识文档。
     * <p>
     * 新文档初始状态为 DRAFT，并创建 versionNo=1 的初始版本。
     * </p>
     *
     * @param datasourceId 数据源 ID
     * @param title        文档标题
     * @param content      文档内容（Markdown）
     * @return 新创建文档的 ID
     */
    @Transactional
    public Long createDoc(Long datasourceId, String title, String content) {
        log.info("创建知识文档 datasourceId={} title={}", datasourceId, title);
        // 构建文档实体，初始状态为草稿
        KnowledgeDoc doc = KnowledgeDoc.builder()
                .datasourceId(datasourceId)
                .title(title)
                .content(content == null ? "" : content)
                .currentVersion(1)
                .status(DocStatus.DRAFT.name())
                .updatedBy(UserContext.currentUserId())
                .deleted(0)
                .build();
        knowledgeDocMapper.insert(doc);
        KnowledgeDocVersion initialVersion = KnowledgeDocVersion.builder()
                .docId(doc.getId())
                .datasourceId(datasourceId)
                .dependencySnapshot(dependencySnapshotBuilder.build(
                        datasourceId,
                        null,
                        GenerationSource.MANUAL.name()))
                .versionNo(1)
                .content(content == null ? "" : content)
                .generationSource(GenerationSource.MANUAL.name())
                .changeSummary("初始创建")
                .createdBy(UserContext.currentUserId())
                .build();
        knowledgeDocVersionMapper.insert(initialVersion);
        log.info("知识文档创建成功 docId={} title={}", doc.getId(), title);
        return doc.getId();
    }

    /**
     * 编辑知识文档（乐观锁校验）。
     * <p>
     * 前端传入版本号用于乐观锁冲突检测，更新失败时抛出业务异常提示刷新。
     * 内容变更时自动创建新版本并重置状态为 DRAFT。
     * </p>
     *
     * @param id           文档 ID
     * @param title        新标题
     * @param content      新内容
     * @param version      前端传入的版本号（乐观锁）
     * @param changeSummary 变更摘要
     */
    @Transactional
    public void updateDoc(Long id, String title, String content, Integer version, String changeSummary) {
        log.info("编辑知识文档 docId={} version={}", id, version);
        KnowledgeDoc doc = helper.requireDoc(id);
        String normalizedContent = content == null ? "" : content;
        boolean contentChanged = !java.util.Objects.equals(doc.getContent(), normalizedContent);
        Integer nextVersionNo = contentChanged
                ? (doc.getCurrentVersion() == null ? 0 : doc.getCurrentVersion()) + 1
                : doc.getCurrentVersion();
        KnowledgeDocVersion currentVersion = null;
        if (contentChanged && doc.getCurrentVersion() != null && doc.getCurrentVersion() > 0) {
            currentVersion = knowledgeDocVersionMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeDocVersion>()
                            .eq(KnowledgeDocVersion::getDocId, doc.getId())
                            .eq(KnowledgeDocVersion::getVersionNo, doc.getCurrentVersion()));
        }
        // 设置乐观锁版本号（MyBatis-Plus @Version 自动处理 WHERE version = ?）
        doc.setVersion(version);
        doc.setTitle(title);
        doc.setContent(normalizedContent);
        doc.setCurrentVersion(nextVersionNo);
        if (contentChanged) {
            doc.setStatus(DocStatus.DRAFT.name());
            doc.setReviewStatus(null);
        }
        doc.setUpdatedBy(UserContext.currentUserId());
        // updateById 返回影响行数，为 0 表示乐观锁冲突
        int rows = knowledgeDocMapper.updateById(doc);
        if (rows == 0) {
            throw new BusinessException("文档已被其他人修改，请刷新后重试");
        }
        if (contentChanged) {
            KnowledgeDocVersion newVersion = KnowledgeDocVersion.builder()
                    .docId(doc.getId())
                    .datasourceId(doc.getDatasourceId())
                    .metadataSnapshotId(currentVersion == null ? null : currentVersion.getMetadataSnapshotId())
                    .dependencySnapshot(dependencySnapshotBuilder.build(
                            doc.getDatasourceId(),
                            currentVersion == null ? null : currentVersion.getMetadataSnapshotId(),
                            GenerationSource.MANUAL.name()))
                    .versionNo(nextVersionNo)
                    .content(normalizedContent)
                    .generationSource(GenerationSource.MANUAL.name())
                    .changeSummary(StringUtils.hasText(changeSummary) ? changeSummary : "人工编辑")
                    .createdBy(UserContext.currentUserId())
                    .build();
            knowledgeDocVersionMapper.insert(newVersion);
        }
        log.info("知识文档编辑成功 docId={}", id);
    }
}
