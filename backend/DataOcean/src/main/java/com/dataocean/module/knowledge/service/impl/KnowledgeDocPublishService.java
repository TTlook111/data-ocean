package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
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
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.entity.TableRelation;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
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
    private final FieldTagMapper fieldTagMapper;
    private final TransactionTemplate transactionTemplate;
    private final KnowledgeDocHelper helper;

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

        // 加载元数据
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
                            GenerationSource.AI_GENERATED.name(),
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
            knowledgeDocMapper.updateById(latestDoc);

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
            throw new BusinessException("AI 批量生成失败，请稍后重试");
        }

        // 解析返回的文档列表
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> docs = (List<Map<String, Object>>) result.get("docs");
        if (docs == null || docs.isEmpty()) {
            throw new BusinessException("AI 批量生成失败：未返回任何文档");
        }

        List<Map<String, Object>> createdDocs = transactionTemplate.execute(status -> {
            List<Map<String, Object>> created = new ArrayList<>();
            for (Map<String, Object> docData : docs) {
                String title = (String) docData.get("title");
                String content = (String) docData.get("content");
                @SuppressWarnings("unchecked")
                List<String> tableNames = (List<String>) docData.get("table_names");

                if (!StringUtils.hasText(content)) {
                    log.warn("跳过空内容文档 title={}", title);
                    continue;
                }

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

                Map<String, Object> createdDoc = new HashMap<>();
                createdDoc.put("id", doc.getId());
                createdDoc.put("title", title);
                createdDoc.put("tableNames", tableNames);
                created.add(createdDoc);

                log.info("AI 批量生成文档成功 docId={} title={}", doc.getId(), title);
            }
            return created;
        });

        log.info("AI 批量生成完成，共创建 {} 份文档", createdDocs.size());
        return createdDocs;
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
    private List<Map<String, Object>> loadTablesMetadata(Long datasourceId) {
        List<DbTableMeta> tables = dbTableMetaMapper.selectList(
                new LambdaQueryWrapper<DbTableMeta>()
                        .eq(DbTableMeta::getDatasourceId, datasourceId));
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
                            .in(DbColumnMeta::getTableMetaId, tableIds));
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
            tableMap.put("table_name", table.getTableName());
            tableMap.put("table_comment", table.getTableComment());

            List<DbColumnMeta> columns = columnsByTable.getOrDefault(table.getId(), List.of());
            // 解析索引信息，提取有索引的字段名集合
            Set<String> indexedColumns = parseIndexedColumns(table.getIndexesInfo());

            List<Map<String, Object>> columnList = new ArrayList<>();
            for (DbColumnMeta col : columns) {
                Map<String, Object> colMap = new HashMap<>();
                colMap.put("column_name", col.getColumnName());
                colMap.put("column_type", col.getDataType());
                colMap.put("column_comment", col.getColumnComment());
                colMap.put("is_primary_key", col.getIsPrimaryKey() != null && col.getIsPrimaryKey() == 1);
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
