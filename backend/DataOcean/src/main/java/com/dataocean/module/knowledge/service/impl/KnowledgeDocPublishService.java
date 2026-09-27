package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.persistence.OptimisticLockSupport;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.knowledge.client.PythonKnowledgeClient;
import com.dataocean.module.knowledge.client.PythonRagClient;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.entity.VectorIndexTask;
import com.dataocean.module.knowledge.enums.DocStatus;
import com.dataocean.module.knowledge.enums.GenerationSource;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.support.KnowledgeDependencySnapshotBuilder;
import com.dataocean.module.knowledge.support.KnowledgeOwnershipValidator;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.entity.TableRelation;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
import com.dataocean.module.metadata.mapper.MetadataRelationshipMapper;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.fieldtag.entity.FieldTag;
import com.dataocean.module.fieldtag.mapper.FieldTagMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 知识文档发布与 AI 生成服务。
 * <p>
 * 负责 AI 草稿生成、批量生成、元数据加载、切片预览等发布相关操作。
 * 从 KnowledgeDocServiceImpl 拆分而来，职责单一。
 * </p>
 *
 * @author DataOcean
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocPublishService {

    /** JSON 解析用的 ObjectMapper（线程安全，复用实例） */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final KnowledgeDocMapper knowledgeDocMapper;
    private final KnowledgeDocVersionMapper knowledgeDocVersionMapper;
    private final PythonKnowledgeClient pythonKnowledgeClient;
    private final PythonRagClient pythonRagClient;
    private final KnowledgeDependencySnapshotBuilder dependencySnapshotBuilder;
    private final DbTableMetaMapper dbTableMetaMapper;
    private final DbColumnMetaMapper dbColumnMetaMapper;
    private final TableRelationMapper tableRelationMapper;
    private final MetadataRelationshipMapper metadataRelationshipMapper;
    private final MetadataEntityService metadataEntityService;
    private final FieldTagMapper fieldTagMapper;
    private final TransactionTemplate transactionTemplate;
    private final KnowledgeDocHelper helper;
    /** 校验请求里的 datasourceId 与快照真实归属一致（B0 §6.3：不得只信任传入的 datasourceId）。 */
    private final MetadataSnapshotMapper metadataSnapshotMapper;
    /** 版本 / 来源快照归属的统一校验点。 */
    private final KnowledgeOwnershipValidator ownershipValidator;

    /**
     * 校验「请求里的 datasourceId」与「快照的真实归属」一致。
     *
     * <p>批量生成会按传入的 `datasourceId` 读取该数据源的表结构并交给 Python。
     * 接口同时收到 `datasourceId` 与 `snapshotId` 时，只校验快照就等于允许
     * “用一个自己负责的快照 + 一个自己无权源的数据源 ID”去读别人数据源的元数据。
     * 因此这里必须读快照的真实归属并逐项比对，不一致直接拒绝。</p>
     */
    private void requireSnapshotBelongsToDatasource(Long datasourceId, Long snapshotId) {
        if (datasourceId == null || snapshotId == null) {
            throw new BusinessException(400, "必须同时指定数据源与快照");
        }
        MetadataSnapshot snapshot = metadataSnapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw new BusinessException(404, "快照不存在");
        }
        if (snapshot.getDatasourceId() == null) {
            throw new BusinessException(409, "快照缺少数据源归属，无法判定负责范围");
        }
        if (!datasourceId.equals(snapshot.getDatasourceId())) {
            throw new BusinessException(409, "快照不属于请求指定的数据源，拒绝生成");
        }
    }

    /**
     * 生成 AI 草稿。
     * <p>
     * 调用 Python AI 服务基于元数据快照生成知识文档草稿内容，
     * 并创建新版本记录。
     * </p>
     *
     * @param docId      文档 ID
     * @param snapshotId 元数据快照 ID
     * @return 生成的草稿内容
     */
    public String generateDraft(Long docId, Long snapshotId) {
        log.info("生成 AI 草稿 docId={} snapshotId={}", docId, snapshotId);
        KnowledgeDoc doc = helper.requireDoc(docId);
        // 本方法自己拼版本行、不经过 createVersion，所以快照归属必须在这里单独校验：
        // 否则一个跨源 snapshotId 会先被送给 Python、再落成一条来源错位的版本。
        ownershipValidator.requireSnapshotOwnership(doc.getDatasourceId(), snapshotId);

        // 加载元数据
        List<Map<String, Object>> tablesMetadata = loadTablesMetadata(doc.getDatasourceId(), snapshotId);
        List<Map<String, Object>> foreignKeys = loadForeignKeys(doc.getDatasourceId(), snapshotId);
        List<Map<String, Object>> lineageFacts = loadConfirmedLineage(doc.getDatasourceId(), snapshotId);

        // 调用 Python AI 服务生成草稿
        String draftContent;
        Map<String, Object> result;
        try {
            result = pythonKnowledgeClient.generateDraft(
                    snapshotId, doc.getDatasourceId(), tablesMetadata, foreignKeys, lineageFacts, List.of());
            draftContent = (String) result.get("content");
            validateDraftCoverage(result, tablesMetadata);
        } catch (Exception e) {
            log.error("AI 草稿生成失败 docId={} snapshotId={}", docId, snapshotId, e);
            throw new BusinessException("AI 草稿生成失败，请稍后重试");
        }
        if (!StringUtils.hasText(draftContent)) {
            throw new BusinessException("AI 草稿生成失败：内容为空");
        }

        transactionTemplate.executeWithoutResult(status -> {
            KnowledgeDoc latestDoc = helper.requireDoc(docId);
            KnowledgeDocVersion version = KnowledgeDocVersion.builder()
                    .docId(docId)
                    .datasourceId(latestDoc.getDatasourceId())
                    .metadataSnapshotId(snapshotId)
                    .dependencySnapshot(dependencySnapshotBuilder.build(
                            latestDoc.getDatasourceId(),
                            snapshotId,
                            GenerationSource.SNAPSHOT_GENERATED.name(),
                            Map.of("warnings", resultWarnings(result))))
                    .versionNo((latestDoc.getCurrentVersion() == null ? 0 : latestDoc.getCurrentVersion()) + 1)
                    .content(draftContent)
                    .generationSource(GenerationSource.AI_GENERATED.name())
                    .changeSummary("AI 自动生成草稿")
                    .createdBy(UserContext.currentUserId())
                    .build();
            knowledgeDocVersionMapper.insert(version);

            latestDoc.setContent(draftContent);
            latestDoc.setCurrentVersion(version.getVersionNo());
            latestDoc.setUpdatedBy(UserContext.currentUserId());
            OptimisticLockSupport.requireUpdated(
                    knowledgeDocMapper.updateById(latestDoc),
                    "知识文档已被其他人修改，请刷新后重试");

            log.info("AI 草稿生成成功 docId={} versionNo={}", docId, version.getVersionNo());
        });
        return draftContent;
    }

    /**
     * AI 自动分析业务域并批量生成 skills.md 文档。
     * <p>
     * 用户选择一个元数据快照，AI 分析表结构识别业务域，
     * 每个域自动创建一份独立的 skills.md 文档（DRAFT 状态）。
     * </p>
     *
     * @param datasourceId 数据源 ID
     * @param snapshotId   元数据快照 ID
     * @return 创建的文档列表（包含 id、title、tableNames）
     */
    public List<Map<String, Object>> batchGenerateFromSnapshot(Long datasourceId, Long snapshotId) {
        log.info("按快照生成完整字段目录 datasourceId={} snapshotId={}", datasourceId, snapshotId);
        requireSnapshotBelongsToDatasource(datasourceId, snapshotId);

        // 结构字段、关系和血缘必须来自同一份已验证快照。
        List<Map<String, Object>> tablesMetadata = loadTablesMetadata(datasourceId, snapshotId);
        List<Map<String, Object>> foreignKeys = loadForeignKeys(datasourceId, snapshotId);
        List<Map<String, Object>> lineageFacts = loadConfirmedLineage(datasourceId, snapshotId);
        Map<String, Object> result;
        try {
            result = pythonKnowledgeClient.generateDraft(
                    snapshotId, datasourceId, tablesMetadata, foreignKeys, lineageFacts, List.of());
            validateDraftCoverage(result, tablesMetadata);
        } catch (Exception e) {
            log.error("按快照生成完整知识目录失败 datasourceId={} snapshotId={}", datasourceId, snapshotId, e);
            throw new BusinessException("快照知识目录生成或字段覆盖校验失败");
        }
        String content = String.valueOf(result.getOrDefault("content", ""));
        if (!StringUtils.hasText(content)) throw new BusinessException("快照知识目录为空");
        List<String> tableNames = tablesMetadata.stream()
                .map(table -> String.valueOf(table.get("table_name")))
                .distinct().sorted().toList();
        String title = "数据源知识目录 · 快照 " + snapshotId;
        List<Map<String, Object>> createdDocs = transactionTemplate.execute(status -> {
            KnowledgeDoc doc = KnowledgeDoc.builder()
                    .datasourceId(datasourceId)
                    .title(title)
                    .content(content)
                    .currentVersion(1)
                    .status(DocStatus.DRAFT.name())
                    .tableNames(toJsonArray(tableNames))
                    .updatedBy(UserContext.currentUserId())
                    .deleted(0)
                    .build();
            knowledgeDocMapper.insert(doc);
            KnowledgeDocVersion version = KnowledgeDocVersion.builder()
                    .docId(doc.getId())
                    .datasourceId(datasourceId)
                    .metadataSnapshotId(snapshotId)
                    .dependencySnapshot(dependencySnapshotBuilder.build(
                            datasourceId, snapshotId, GenerationSource.SNAPSHOT_GENERATED.name(),
                            Map.of("coverage", result.getOrDefault("coverage", Map.of()),
                                    "warnings", resultWarnings(result))))
                    .versionNo(1)
                    .content(content)
                    .generationSource(GenerationSource.SNAPSHOT_GENERATED.name())
                    .changeSummary("从同一元数据快照生成完整字段目录")
                    .createdBy(UserContext.currentUserId())
                    .build();
            knowledgeDocVersionMapper.insert(version);
            Map<String, Object> created = new HashMap<>();
            created.put("id", doc.getId());
            created.put("title", title);
            created.put("tableNames", tableNames);
            created.put("snapshotId", snapshotId);
            return List.of(created);
        });
        log.info("完整快照知识目录生成成功 datasourceId={} snapshotId={}", datasourceId, snapshotId);
        return createdDocs == null ? List.of() : createdDocs;
    }

    /**
     * 预览文档切片结果。
     * <p>
     * 模拟发布时的切片逻辑，返回当前内容会被切成哪些 chunks，
     * 供作者在发布前预览 RAG 检索效果。
     * </p>
     *
     * @param docId 文档 ID
     * @return 切片预览列表，每个元素包含 chunk_text 和 chunk_type
     */
    public List<Map<String, String>> previewChunks(Long docId) {
        KnowledgeDoc doc = helper.requireDoc(docId);
        VectorIndexTask previewTask = VectorIndexTask.builder()
                .datasourceId(doc.getDatasourceId())
                .targetType("DOC")
                .targetId(doc.getId())
                .knowledgeVersionNo(doc.getCurrentVersion())
                .build();
        return pythonRagClient.chunkDocument(previewTask, doc.getContent()).stream()
                .map(this::toStringMap)
                .toList();
    }

    /**
     * 加载数据源的表元数据列表（含字段信息、治理状态、标签、索引）。
     *
     * @param datasourceId 数据源 ID
     * @return 表元数据列表（Map 格式，供 Python 接口使用）
     */
    private List<Map<String, Object>> loadTablesMetadata(Long datasourceId, Long snapshotId) {
        List<DbTableMeta> tables = dbTableMetaMapper.selectList(
                new LambdaQueryWrapper<DbTableMeta>()
                        .eq(DbTableMeta::getDatasourceId, datasourceId)
                        .eq(DbTableMeta::getSnapshotId, snapshotId)
                        .orderByAsc(DbTableMeta::getTableName));
        if (tables == null || tables.isEmpty()) {
            return new ArrayList<>();
        }

        // 查询所有字段
        List<Long> tableIds = tables.stream().map(DbTableMeta::getId).toList();
        Map<Long, List<DbColumnMeta>> columnsByTable = new HashMap<>();
        Map<Long, List<String>> tagsByColumn = new HashMap<>();

        if (!tableIds.isEmpty()) {
            List<DbColumnMeta> allColumns = dbColumnMetaMapper.selectList(
                    new LambdaQueryWrapper<DbColumnMeta>()
                            .in(DbColumnMeta::getTableMetaId, tableIds)
                            .eq(DbColumnMeta::getDatasourceId, datasourceId)
                            .eq(DbColumnMeta::getSnapshotId, snapshotId)
                            .orderByAsc(DbColumnMeta::getOrdinalPosition));
            if (allColumns != null && !allColumns.isEmpty()) {
                columnsByTable = allColumns.stream()
                        .collect(java.util.stream.Collectors.groupingBy(DbColumnMeta::getTableMetaId));
                // 加载所有字段的标签信息
                List<Long> columnIds = allColumns.stream().map(DbColumnMeta::getId).toList();
                tagsByColumn = loadFieldTags(columnIds);
            }
        }

        // 组装结果（无论是否有字段，表信息都要返回）
        List<Map<String, Object>> tablesMetadata = new ArrayList<>();
        for (DbTableMeta table : tables) {
            Map<String, Object> tableMap = new HashMap<>();
            tableMap.put("table_id", table.getId());
            tableMap.put("source_snapshot_id", table.getSnapshotId());
            tableMap.put("table_name", table.getTableName());
            tableMap.put("table_comment", table.getTableComment());
            tableMap.put("table_type", table.getTableType());
            tableMap.put("governance_status", table.getGovernanceStatus());

            List<DbColumnMeta> columns = columnsByTable.getOrDefault(table.getId(), List.of());
            // 解析索引信息，提取有索引的字段名集合
            Set<String> indexedColumns = parseIndexedColumns(table.getIndexesInfo());

            List<Map<String, Object>> columnList = new ArrayList<>();
            for (DbColumnMeta col : columns) {
                Map<String, Object> colMap = new HashMap<>();
                colMap.put("column_id", col.getId());
                colMap.put("source_snapshot_id", col.getSnapshotId());
                colMap.put("column_name", col.getColumnName());
                colMap.put("column_type", col.getDataType());
                colMap.put("column_comment", col.getColumnComment());
                colMap.put("is_primary_key", col.getIsPrimaryKey() != null && col.getIsPrimaryKey() == 1);
                colMap.put("ordinal_position", col.getOrdinalPosition());
                colMap.put("confidence_score", col.getConfidenceScore());
                colMap.put("governance_status", col.getGovernanceStatus());
                colMap.put("tags", tagsByColumn.getOrDefault(col.getId(), List.of()));
                colMap.put("is_indexed", indexedColumns.contains(col.getColumnName()));
                columnList.add(colMap);
            }
            tableMap.put("columns", columnList);
            tablesMetadata.add(tableMap);
        }
        return tablesMetadata;
    }

    /**
     * 批量加载字段标签，按 columnMetaId 分组。
     *
     * @param columnIds 字段 ID 列表
     * @return 标签映射（key: columnMetaId, value: 标签名列表）
     */
    private Map<Long, List<String>> loadFieldTags(List<Long> columnIds) {
        if (columnIds == null || columnIds.isEmpty()) {
            return new HashMap<>();
        }
        List<FieldTag> tags = fieldTagMapper.selectList(
                new LambdaQueryWrapper<FieldTag>()
                        .in(FieldTag::getColumnMetaId, columnIds));
        Map<Long, List<String>> result = new HashMap<>();
        if (tags == null) {
            return result;
        }
        for (FieldTag tag : tags) {
            result.computeIfAbsent(tag.getColumnMetaId(), k -> new ArrayList<>())
                    .add(tag.getTagName());
        }
        return result;
    }

    /**
     * 从表的 indexesInfo JSON 字段中解析出有索引的字段名集合。
     * <p>
     * indexesInfo 格式示例：[{"indexName":"idx_user_name","columnName":"user_name","nonUnique":true}]
     * </p>
     */
    private Set<String> parseIndexedColumns(String indexesInfoJson) {
        Set<String> result = new HashSet<>();
        if (!StringUtils.hasText(indexesInfoJson)) {
            return result;
        }
        try {
            JsonNode array = JSON_MAPPER.readTree(indexesInfoJson);
            if (array.isArray()) {
                for (JsonNode node : array) {
                    String colName = node.has("columnName") ? node.get("columnName").asText() : null;
                    if (colName == null) {
                        colName = node.has("column_name") ? node.get("column_name").asText() : null;
                    }
                    if (colName != null && !colName.isEmpty()) {
                        result.add(colName);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析索引信息 JSON 失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 加载数据源的外键关系列表。
     *
     * @param datasourceId 数据源 ID
     * @return 外键关系列表（Map 格式，供 Python 接口使用）
     */
    private List<Map<String, Object>> loadForeignKeys(Long datasourceId, Long snapshotId) {
        List<TableRelation> relations = tableRelationMapper.selectList(
                new LambdaQueryWrapper<TableRelation>()
                        .eq(TableRelation::getDatasourceId, datasourceId)
                        .eq(TableRelation::getSnapshotId, snapshotId)
                        .orderByAsc(TableRelation::getId));
        List<Map<String, Object>> foreignKeys = new ArrayList<>();
        for (TableRelation rel : relations) {
            Map<String, Object> fk = new HashMap<>();
            fk.put("relation_id", rel.getId());
            fk.put("snapshot_id", rel.getSnapshotId());
            fk.put("source_table", rel.getSourceTable());
            fk.put("source_column", rel.getSourceColumn());
            fk.put("target_table", rel.getTargetTable());
            fk.put("target_column", rel.getTargetColumn());
            fk.put("relation_type", rel.getRelationType());
            fk.put("confidence", rel.getConfidence());
            String reviewStatus = rel.getReviewStatus();
            if (reviewStatus == null || reviewStatus.isBlank()) {
                reviewStatus = TableRelation.TYPE_FK.equals(rel.getRelationType()) ? "APPROVED" : "PENDING";
            }
            fk.put("review_status", reviewStatus);
            foreignKeys.add(fk);
        }
        return foreignKeys;
    }

    private List<Map<String, Object>> loadConfirmedLineage(Long datasourceId, Long snapshotId) {
        List<MetadataEntity> entities = metadataEntityService.getByDatasourceId(datasourceId);
        if (entities == null || entities.isEmpty()) return List.of();
        List<Long> entityIds = entities.stream().map(MetadataEntity::getId).filter(java.util.Objects::nonNull).toList();
        if (entityIds.isEmpty()) return List.of();
        Map<Long, MetadataEntity> entityById = entities.stream()
                .filter(entity -> entity.getId() != null)
                .collect(java.util.stream.Collectors.toMap(MetadataEntity::getId, entity -> entity, (left, right) -> left));
        List<MetadataRelationship> relationships = metadataRelationshipMapper.selectByEntityIds(entityIds);
        List<Map<String, Object>> facts = new ArrayList<>();
        for (MetadataRelationship relationship : relationships) {
            if (!MetadataRelationship.TYPE_LINEAGE.equals(relationship.getRelationType())
                    && !MetadataRelationship.TYPE_DERIVED_FROM.equals(relationship.getRelationType())) continue;
            Map<String, Object> metadata = readJsonMap(relationship.getRelationMetadata());
            if (!"CONFIRMED".equalsIgnoreCase(String.valueOf(metadata.get("confirmation_status")))
                    || !"BOUND".equalsIgnoreCase(String.valueOf(metadata.get("binding_status")))
                    || !String.valueOf(snapshotId).equals(String.valueOf(metadata.get("bound_snapshot_id")))) continue;
            MetadataEntity source = entityById.get(relationship.getSourceId());
            MetadataEntity target = entityById.get(relationship.getTargetId());
            String sourceFqn = valueOr(metadata.get("source_fqn"), source == null ? null : source.getFqn());
            String targetFqn = valueOr(metadata.get("target_fqn"), target == null ? null : target.getFqn());
            if (sourceFqn == null || targetFqn == null) continue;
            Map<String, Object> fact = new HashMap<>();
            fact.put("relationship_id", relationship.getId());
            fact.put("source_fqn", sourceFqn);
            fact.put("target_fqn", targetFqn);
            fact.put("relation_type", relationship.getRelationType());
            fact.put("source_snapshot_id", metadata.get("source_snapshot_id"));
            fact.put("target_snapshot_id", metadata.get("target_snapshot_id"));
            fact.put("bound_snapshot_id", snapshotId);
            fact.put("binding_status", "BOUND");
            fact.put("confirmation_status", "CONFIRMED");
            fact.put("review_status", "APPROVED");
            fact.put("description", metadata.get("description"));
            fact.put("lineage_type", metadata.get("lineage_type"));
            fact.put("column_mappings", metadata.get("column_mappings"));
            fact.put("dependencies", lineageDependencies(sourceFqn, targetFqn));
            facts.add(fact);
        }
        return facts;
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON_MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<String> lineageDependencies(String sourceFqn, String targetFqn) {
        List<String> result = new ArrayList<>();
        addFqnDependencies(result, sourceFqn);
        addFqnDependencies(result, targetFqn);
        return result.stream().distinct().toList();
    }

    private void addFqnDependencies(List<String> result, String fqn) {
        String[] parts = fqn.split("\\.");
        if (parts.length >= 3) result.add("table:" + parts[parts.length - 2]);
        if (parts.length >= 4) result.add("column:" + parts[parts.length - 2] + "." + parts[parts.length - 1]);
    }

    private String valueOr(Object preferred, String fallback) {
        if (preferred != null && !String.valueOf(preferred).isBlank()) return String.valueOf(preferred);
        return fallback;
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

    private void validateDraftCoverage(Map<String, Object> result, List<Map<String, Object>> tablesMetadata) {
        Object rawCoverage = result.get("coverage");
        if (!(rawCoverage instanceof Map<?, ?> coverage)) {
            throw new BusinessException("知识草稿缺少快照字段覆盖证明");
        }
        Set<Long> expectedTables = new HashSet<>();
        Set<Long> expectedColumns = new HashSet<>();
        for (Map<String, Object> table : tablesMetadata) {
            Long tableId = numericId(table.get("table_id"));
            if (tableId == null || !Objects.equals(numericId(table.get("source_snapshot_id")),
                    numericId(coverage.get("snapshotId")))) {
                throw new BusinessException("表结构输入缺少快照归属或 ID");
            }
            expectedTables.add(tableId);
            Object rawColumns = table.get("columns");
            if (rawColumns instanceof List<?> columns) {
                for (Object rawColumn : columns) {
                    if (!(rawColumn instanceof Map<?, ?> column)) continue;
                    Long columnId = numericId(column.get("column_id"));
                    if (columnId == null || !Objects.equals(numericId(column.get("source_snapshot_id")),
                            numericId(coverage.get("snapshotId")))) {
                        throw new BusinessException("字段结构输入缺少快照归属或 ID");
                    }
                    expectedColumns.add(columnId);
                }
            }
        }
        Set<Long> actualTables = numericIdSet(coverage.get("tableIds"));
        Set<Long> actualColumns = numericIdSet(coverage.get("columnIds"));
        if (!expectedTables.equals(actualTables)
                || !expectedColumns.equals(actualColumns)
                || !Double.valueOf(1.0).equals(asDouble(coverage.get("tableCoverage")))
                || !Double.valueOf(1.0).equals(asDouble(coverage.get("columnCoverage")))) {
            throw new BusinessException("知识草稿字段覆盖不是 100%，保持草稿未发布");
        }
    }

    private Set<Long> numericIdSet(Object value) {
        if (!(value instanceof List<?> items)) return Set.of();
        Set<Long> result = new HashSet<>();
        for (Object item : items) {
            Long id = numericId(item);
            if (id == null || !result.add(id)) return Set.of();
        }
        return result;
    }

    private Long numericId(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double asDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null ? null : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
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
     * 将 Map&lt;String, Object&gt; 转换为 Map&lt;String, String&gt;。
     * <p>
     * 转换规则：
     * <ul>
     *   <li>null 值转换为空字符串 ""</li>
     *   <li>其他值调用 String.valueOf() 转换为字符串</li>
     * </ul>
     * 用途：Python 接口要求参数为字符串类型，需要将 Java 的 Object 值统一转换。
     * </p>
     *
     * @param payload 原始参数 Map，value 可能为任意类型
     * @return 转换后的 Map，所有 value 为 String 类型
     */
    private Map<String, String> toStringMap(Map<String, Object> payload) {
        Map<String, String> result = new HashMap<>();
        payload.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
        return result;
    }
}
