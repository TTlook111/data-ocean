package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.entity.KnowledgeReviewTask;
import com.dataocean.module.knowledge.enums.DocStatus;
import com.dataocean.module.knowledge.enums.GenerationSource;
import com.dataocean.module.knowledge.enums.ReviewStatus;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeReviewTaskMapper;
import com.dataocean.module.knowledge.service.KnowledgeDocService;
import com.dataocean.module.knowledge.service.VectorIndexTaskService;
import com.dataocean.module.knowledge.client.PythonKnowledgeClient;
import com.dataocean.module.knowledge.support.KnowledgeChunkSplitter;
import com.dataocean.module.knowledge.support.KnowledgeDependencySnapshotBuilder;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.entity.TableRelation;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识文档管理业务实现类。
 * <p>
 * 实现 {@link KnowledgeDocService} 接口，提供文档 CRUD、状态流转、AI 草稿生成等完整管理功能。
 * 文档生命周期：DRAFT → PENDING_REVIEW → APPROVED → PUBLISHED。
 * 发布时自动切分 chunks 并创建向量化任务。
 * </p>
 *
 * @author DataOcean
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocServiceImpl implements KnowledgeDocService {

    private final KnowledgeDocMapper knowledgeDocMapper;
    private final KnowledgeDocVersionMapper knowledgeDocVersionMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeReviewTaskMapper knowledgeReviewTaskMapper;
    private final VectorIndexTaskService vectorIndexTaskService;
    private final PythonKnowledgeClient pythonKnowledgeClient;
    private final KnowledgeDependencySnapshotBuilder dependencySnapshotBuilder;
    private final KnowledgeChunkSplitter knowledgeChunkSplitter;
    private final DbTableMetaMapper dbTableMetaMapper;
    private final DbColumnMetaMapper dbColumnMetaMapper;
    private final TableRelationMapper tableRelationMapper;

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：使用 LambdaQueryWrapper 动态条件查询，支持按 datasourceId 和 status 筛选。
     * </p>
     */
    @Override
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
     * {@inheritDoc}
     */
    @Override
    public KnowledgeDoc getDocById(Long id) {
        return requireDoc(id);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：创建文档，初始状态 DRAFT，并创建初始版本。
     * </p>
     */
    @Transactional
    @Override
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
     * {@inheritDoc}
     * <p>
     * 实现逻辑：乐观锁校验，设置 entity 的 version 字段后 updateById，
     * MyBatis-Plus 自动处理版本比对，更新失败抛出业务异常。
     * </p>
     */
    @Transactional
    @Override
    public void updateDoc(Long id, String title, String content, Integer version, String changeSummary) {
        log.info("编辑知识文档 docId={} version={}", id, version);
        KnowledgeDoc doc = requireDoc(id);
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

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：校验状态为 DRAFT，更新为 PENDING_REVIEW。
     * </p>
     */
    @Transactional
    @Override
    public void submitReview(Long id) {
        log.info("提交文档审核 docId={}", id);
        KnowledgeDoc doc = requireDoc(id);
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
     * {@inheritDoc}
     * <p>
     * 实现逻辑：校验状态为 PENDING_REVIEW，更新为 APPROVED，创建审核任务记录。
     * </p>
     */
    @Transactional
    @Override
    public void approve(Long id, String comment) {
        log.info("审核通过文档 docId={}", id);
        KnowledgeDoc doc = requireDoc(id);
        // 校验当前状态必须为待审核
        if (!DocStatus.PENDING_REVIEW.name().equals(doc.getStatus())) {
            throw new BusinessException("只有待审核状态的文档才能审核");
        }
        // 更新文档状态为审核通过
        doc.setStatus(DocStatus.APPROVED.name());
        doc.setReviewStatus(ReviewStatus.APPROVED.name());
        doc.setUpdatedBy(UserContext.currentUserId());
        knowledgeDocMapper.updateById(doc);
        // 创建审核任务记录
        createReviewTask(doc, ReviewStatus.APPROVED.name(), comment);
        log.info("文档审核通过 docId={}", id);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：校验状态为 PENDING_REVIEW，更新为 DRAFT，创建审核任务记录。
     * </p>
     */
    @Transactional
    @Override
    public void reject(Long id, String comment) {
        log.info("审核拒绝文档 docId={}", id);
        KnowledgeDoc doc = requireDoc(id);
        // 校验当前状态必须为待审核
        if (!DocStatus.PENDING_REVIEW.name().equals(doc.getStatus())) {
            throw new BusinessException("只有待审核状态的文档才能审核");
        }
        // 更新文档状态回退为草稿
        doc.setStatus(DocStatus.DRAFT.name());
        doc.setReviewStatus(ReviewStatus.REJECTED.name());
        doc.setUpdatedBy(UserContext.currentUserId());
        knowledgeDocMapper.updateById(doc);
        // 创建审核任务记录
        createReviewTask(doc, ReviewStatus.REJECTED.name(), comment);
        log.info("文档审核拒绝 docId={}", id);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：
     * 1. 校验状态为 APPROVED
     * 2. 发布前校验引用字段的治理状态
     * 3. 更新为 PUBLISHED
     * 4. 切分文档内容为 chunks
     * 5. 创建向量化任务
     * </p>
     */
    @Transactional
    @Override
    public void publish(Long id) {
        log.info("发布知识文档 docId={}", id);
        KnowledgeDoc doc = requireDoc(id);
        Integer previousPublishedVersionNo = findCurrentPublishedVersionNo(doc.getId());
        boolean rebuildCurrentVersion = previousPublishedVersionNo != null
                && previousPublishedVersionNo.equals(doc.getCurrentVersion());
        // 校验当前状态必须为审核通过
        if (!DocStatus.APPROVED.name().equals(doc.getStatus())) {
            throw new BusinessException("只有审核通过的文档才能发布");
        }
        // 发布前校验：检查引用字段的治理状态
        validateBeforePublish(doc);
        // 更新文档状态为已发布
        doc.setStatus(DocStatus.PUBLISHED.name());
        doc.setUpdatedBy(UserContext.currentUserId());
        knowledgeDocMapper.updateById(doc);
        // 切分文档内容为 chunks
        KnowledgeDocVersion currentVersion = requireVersion(doc.getId(), doc.getCurrentVersion());
        knowledgeChunkSplitter.splitAndSave(
                doc.getId(), doc.getCurrentVersion(),
                currentVersion.getMetadataSnapshotId(), doc.getContent());
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
     * {@inheritDoc}
     * <p>
     * 实现逻辑：
     * 1. 校验文档存在性
     * 2. 从元数据表中读取快照关联的表和字段信息
     * 3. 调用 Python AI 服务生成草稿
     * 4. 创建新版本记录并同步更新文档主表
     * </p>
     */
    @Transactional
    @Override
    public String generateDraft(Long docId, Long snapshotId) {
        log.info("生成 AI 草稿 docId={} snapshotId={}", docId, snapshotId);
        KnowledgeDoc doc = requireDoc(docId);

        // 加载元数据（复用公共方法）
        List<Map<String, Object>> tablesMetadata = loadTablesMetadata(doc.getDatasourceId());
        List<Map<String, Object>> foreignKeys = loadForeignKeys(doc.getDatasourceId());

        // 调用 Python AI 服务生成草稿
        String draftContent;
        Map<String, Object> result;
        try {
            result = pythonKnowledgeClient.generateDraft(
                    snapshotId, doc.getDatasourceId(), tablesMetadata, foreignKeys, List.of());
            draftContent = (String) result.get("content");
        } catch (Exception e) {
            log.error("AI 草稿生成失败 docId={} snapshotId={}", docId, snapshotId, e);
            throw new BusinessException("AI 草稿生成失败：" + e.getMessage());
        }
        if (!StringUtils.hasText(draftContent)) {
            throw new BusinessException("AI 草稿生成失败：内容为空");
        }

        // 创建新版本记录
        KnowledgeDocVersion version = KnowledgeDocVersion.builder()
                .docId(docId)
                .datasourceId(doc.getDatasourceId())
                .metadataSnapshotId(snapshotId)
                .dependencySnapshot(dependencySnapshotBuilder.build(
                        doc.getDatasourceId(),
                        snapshotId,
                        GenerationSource.AI_GENERATED.name(),
                        Map.of("warnings", resultWarnings(result))))
                .versionNo((doc.getCurrentVersion() == null ? 0 : doc.getCurrentVersion()) + 1)
                .content(draftContent)
                .generationSource(GenerationSource.AI_GENERATED.name())
                .changeSummary("AI 自动生成草稿")
                .createdBy(UserContext.currentUserId())
                .build();
        knowledgeDocVersionMapper.insert(version);

        // 同步更新文档主表
        doc.setContent(draftContent);
        doc.setCurrentVersion(version.getVersionNo());
        doc.setUpdatedBy(UserContext.currentUserId());
        knowledgeDocMapper.updateById(doc);

        log.info("AI 草稿生成成功 docId={} versionNo={}", docId, version.getVersionNo());
        return draftContent;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：
     * 1. 加载数据源的元数据（表、字段、外键）
     * 2. 调用 Python 服务分析业务域并批量生成 skills.md
     * 3. 对每份文档自动创建 KnowledgeDoc + 初始版本
     * </p>
     */
    @Transactional
    @Override
    public List<Map<String, Object>> batchGenerateFromSnapshot(Long datasourceId, Long snapshotId) {
        log.info("AI 批量生成 skills.md datasourceId={} snapshotId={}", datasourceId, snapshotId);

        // 加载元数据
        List<Map<String, Object>> tablesMetadata = loadTablesMetadata(datasourceId);
        List<Map<String, Object>> foreignKeys = loadForeignKeys(datasourceId);

        // 调用 Python 服务
        Map<String, Object> result;
        try {
            result = pythonKnowledgeClient.analyzeAndGenerate(
                    snapshotId, datasourceId, tablesMetadata, foreignKeys, List.of());
        } catch (Exception e) {
            log.error("AI 域分析+批量生成失败 datasourceId={} snapshotId={}", datasourceId, snapshotId, e);
            throw new BusinessException("AI 批量生成失败：" + e.getMessage());
        }

        // 解析返回的文档列表
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> docs = (List<Map<String, Object>>) result.get("docs");
        if (docs == null || docs.isEmpty()) {
            throw new BusinessException("AI 批量生成失败：未返回任何文档");
        }

        // 逐个创建文档
        List<Map<String, Object>> createdDocs = new ArrayList<>();
        for (Map<String, Object> docData : docs) {
            String title = (String) docData.get("title");
            String content = (String) docData.get("content");
            @SuppressWarnings("unchecked")
            List<String> tableNames = (List<String>) docData.get("table_names");

            if (!StringUtils.hasText(content)) {
                log.warn("跳过空内容文档 title={}", title);
                continue;
            }

            // 创建文档
            KnowledgeDoc doc = KnowledgeDoc.builder()
                    .datasourceId(datasourceId)
                    .title(title)
                    .content(content)
                    .currentVersion(1)
                    .status(DocStatus.DRAFT.name())
                    .tableNames(tableNames != null ? toJsonArray(tableNames) : null)
                    .updatedBy(UserContext.currentUserId())
                    .deleted(0)
                    .build();
            knowledgeDocMapper.insert(doc);

            // 创建初始版本
            KnowledgeDocVersion version = KnowledgeDocVersion.builder()
                    .docId(doc.getId())
                    .datasourceId(datasourceId)
                    .metadataSnapshotId(snapshotId)
                    .dependencySnapshot(dependencySnapshotBuilder.build(
                            datasourceId, snapshotId, GenerationSource.AI_GENERATED.name()))
                    .versionNo(1)
                    .content(content)
                    .generationSource(GenerationSource.AI_GENERATED.name())
                    .changeSummary("AI 自动分析业务域并生成")
                    .createdBy(UserContext.currentUserId())
                    .build();
            knowledgeDocVersionMapper.insert(version);

            // 返回结果
            Map<String, Object> created = new HashMap<>();
            created.put("id", doc.getId());
            created.put("title", title);
            created.put("tableNames", tableNames);
            createdDocs.add(created);

            log.info("AI 批量生成文档成功 docId={} title={}", doc.getId(), title);
        }

        log.info("AI 批量生成完成，共创建 {} 份文档", createdDocs.size());
        return createdDocs;
    }

    /**
     * 加载数据源的表元数据列表（含字段信息）。
     */
    private List<Map<String, Object>> loadTablesMetadata(Long datasourceId) {
        List<DbTableMeta> tables = dbTableMetaMapper.selectList(
                new LambdaQueryWrapper<DbTableMeta>()
                        .eq(DbTableMeta::getDatasourceId, datasourceId));

        List<Long> tableIds = tables.stream().map(DbTableMeta::getId).toList();
        Map<Long, List<DbColumnMeta>> columnsByTable = Map.of();
        if (!tableIds.isEmpty()) {
            List<DbColumnMeta> allColumns = dbColumnMetaMapper.selectList(
                    new LambdaQueryWrapper<DbColumnMeta>()
                            .in(DbColumnMeta::getTableMetaId, tableIds));
            columnsByTable = allColumns.stream()
                    .collect(java.util.stream.Collectors.groupingBy(DbColumnMeta::getTableMetaId));
        }

        List<Map<String, Object>> tablesMetadata = new ArrayList<>();
        for (DbTableMeta table : tables) {
            Map<String, Object> tableMap = new HashMap<>();
            tableMap.put("table_name", table.getTableName());
            tableMap.put("table_comment", table.getTableComment());

            List<DbColumnMeta> columns = columnsByTable.getOrDefault(table.getId(), List.of());
            List<Map<String, Object>> columnList = new ArrayList<>();
            for (DbColumnMeta col : columns) {
                Map<String, Object> colMap = new HashMap<>();
                colMap.put("column_name", col.getColumnName());
                colMap.put("column_type", col.getDataType());
                colMap.put("column_comment", col.getColumnComment());
                colMap.put("is_primary_key", col.getIsPrimaryKey() != null && col.getIsPrimaryKey() == 1);
                colMap.put("confidence_score", col.getConfidenceScore());
                columnList.add(colMap);
            }
            tableMap.put("columns", columnList);
            tablesMetadata.add(tableMap);
        }
        return tablesMetadata;
    }

    /**
     * 加载数据源的外键关系列表。
     */
    private List<Map<String, Object>> loadForeignKeys(Long datasourceId) {
        List<TableRelation> relations = tableRelationMapper.selectList(
                new LambdaQueryWrapper<TableRelation>()
                        .eq(TableRelation::getDatasourceId, datasourceId));
        List<Map<String, Object>> foreignKeys = new ArrayList<>();
        for (TableRelation rel : relations) {
            Map<String, Object> fk = new HashMap<>();
            fk.put("source_table", rel.getSourceTable());
            fk.put("source_column", rel.getSourceColumn());
            fk.put("target_table", rel.getTargetTable());
            fk.put("target_column", rel.getTargetColumn());
            foreignKeys.add(fk);
        }
        return foreignKeys;
    }

    /**
     * 从 AI 生成结果中安全提取 warnings 列表。
     */
    private List<?> resultWarnings(Map<String, Object> result) {
        Object warnings = result.get("warnings");
        if (warnings instanceof List<?> list) {
            return list;
        }
        return List.of();
    }

    /**
     * 将字符串列表转换为 JSON 数组格式字符串。
     * 例如：["orders", "payments"]
     */
    private String toJsonArray(List<String> items) {
        return "[" + items.stream()
                .map(s -> "\"" + s.replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(","))
                + "]";
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
     * 根据 ID 查询文档，不存在则抛出业务异常。
     *
     * @param id 文档 ID
     * @return 文档实体
     * @throws BusinessException 文档不存在时抛出
     */
    private KnowledgeDoc requireDoc(Long id) {
        KnowledgeDoc doc = knowledgeDocMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }
        return doc;
    }

    /**
     * 根据文档 ID 和版本号查询版本记录，不存在或缺少快照则抛出业务异常。
     *
     * @param docId     文档 ID
     * @param versionNo 版本号
     * @return 版本实体
     * @throws BusinessException 版本不存在或缺少元数据快照时抛出
     */
    private KnowledgeDocVersion requireVersion(Long docId, Integer versionNo) {
        KnowledgeDocVersion version = knowledgeDocVersionMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocVersion>()
                        .eq(KnowledgeDocVersion::getDocId, docId)
                        .eq(KnowledgeDocVersion::getVersionNo, versionNo));
        if (version == null) {
            throw new BusinessException("文档版本不存在，无法发布到 RAG");
        }
        if (version.getMetadataSnapshotId() == null) {
            throw new BusinessException("文档版本缺少元数据快照，无法发布到 RAG");
        }
        return version;
    }

    /**
     * 查找文档当前已发布到 RAG 的版本号。
     * <p>
     * 通过查询 vector_status=INDEXED 的最新切片版本号来确定。
     * </p>
     *
     * @param docId 文档 ID
     * @return 当前已发布的版本号，未发布过则返回 null
     */
    private Integer findCurrentPublishedVersionNo(Long docId) {
        List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getDocId, docId)
                        .eq(KnowledgeChunk::getVectorStatus, "INDEXED")
                        .orderByDesc(KnowledgeChunk::getVersionNo)
                        .last("LIMIT 1"));
        if (chunks.isEmpty()) {
            return null;
        }
        return chunks.get(0).getVersionNo();
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
        KnowledgeDocVersion currentVersion = knowledgeDocVersionMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocVersion>()
                        .eq(KnowledgeDocVersion::getDocId, doc.getId())
                        .eq(KnowledgeDocVersion::getVersionNo, doc.getCurrentVersion()));
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

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：模拟切片，按 Markdown 二级标题拆分文档内容，
     * 返回每个切片的文本和类型，不写入数据库。
     * </p>
     */
    @Override
    public List<Map<String, String>> previewChunks(Long docId) {
        KnowledgeDoc doc = requireDoc(docId);
        return knowledgeChunkSplitter.preview(doc.getContent());
    }
}
