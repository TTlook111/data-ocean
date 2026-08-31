package com.dataocean.module.knowledge.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.client.PythonRagClient;
import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.entity.VectorIndexTask;
import com.dataocean.module.knowledge.enums.DocStatus;
import com.dataocean.module.knowledge.enums.ReviewStatus;
import com.dataocean.module.knowledge.enums.VectorTaskStatus;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.service.VectorIndexTaskService;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * Builds RAG indexes for published skills.md versions.
 *
 * Java owns lifecycle and task state. Python owns chunking, embedding, Milvus
 * writes, retrieval, and reranking.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VectorIndexTaskScheduler {

    private final VectorIndexTaskService vectorIndexTaskService;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final KnowledgeDocVersionMapper knowledgeDocVersionMapper;
    private final PythonRagClient pythonRagClient;
    private final TransactionTemplate transactionTemplate;
    private final com.dataocean.module.knowledge.service.KnowledgeChunkService knowledgeChunkService;
    private final MetadataEntityService metadataEntityService;

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @Scheduled(fixedDelay = 300000)
    public void processVectorTasks() {
        List<VectorIndexTask> pendingTasks = vectorIndexTaskService.listPendingTasks();
        if (pendingTasks.isEmpty()) {
            return;
        }
        log.info("扫描到待处理向量化任务 count={}", pendingTasks.size());
        for (VectorIndexTask task : pendingTasks) {
            try {
                vectorIndexTaskService.markProcessing(task.getId());
                processTask(task);
                log.info("向量化任务处理完成 taskId={} targetType={} targetId={}",
                        task.getId(), task.getTargetType(), task.getTargetId());
            } catch (Exception e) {
                log.error("向量化任务处理失败 taskId={}", task.getId(), e);
                vectorIndexTaskService.markFailed(task.getId(), e.getMessage());
                restoreDocAfterFailure(task);
            }
        }
    }

    public void processTask(VectorIndexTask task) {
        if (VectorTaskStatus.CLEANUP_PENDING.name().equals(task.getStatus())) {
            retryOldVectorCleanup(task);
            return;
        }

        if (!"DOC".equals(task.getTargetType()) && !"KNOWLEDGE_DOC".equals(task.getTargetType())) {
            throw new BusinessException("暂不支持的向量化目标类型：" + task.getTargetType());
        }
        if (task.getMetadataSnapshotId() == null || task.getKnowledgeVersionNo() == null) {
            throw new BusinessException("向量化任务缺少快照或版本上下文，请重新发布 skills.md");
        }

        KnowledgeDocVersion version = requireVersion(task);
        List<KnowledgeChunk> chunks = chunkAndSave(task, version.getContent());
        boolean forceRebuild = Objects.equals(task.getPreviousVersionNo(), task.getKnowledgeVersionNo());

        Map<String, Object> response = pythonRagClient.vectorize(task, chunks, forceRebuild);
        String status = String.valueOf(response.getOrDefault("status", ""));
        int vectorizedCount = toInt(response.get("vectorizedCount"));
        if (!"COMPLETED".equals(status) || vectorizedCount != chunks.size()) {
            throw new BusinessException("RAG 向量化未完成，status=" + status + " vectorizedCount=" + vectorizedCount);
        }

        try {
            // 先提交 Java 的 chunk、文档和任务状态。旧版本向量清理属于提交后的
            // 补偿动作，失败时只重试清理，不回滚已经发布的新版本。
            transactionTemplate.executeWithoutResult(transactionStatus -> {
                markChunksIndexed(task);
                markDocumentPublished(task);
                vectorIndexTaskService.markCompleted(task.getId());
            });
        } catch (RuntimeException e) {
            // 发布事务失败时，新版本向量不能继续作为可召回数据；旧版本尚未清理，
            // 因此这里只补偿删除当前版本并把原异常继续交给失败流程。
            cleanupCurrentVectorsAfterPublishFailure(task);
            throw e;
        }

        cleanupOldVectorsAfterPublish(task);
    }

    private void cleanupOldVectorsAfterPublish(VectorIndexTask task) {
        if (task.getPreviousVersionNo() == null
                || Objects.equals(task.getPreviousVersionNo(), task.getKnowledgeVersionNo())) {
            return;
        }

        boolean cleanupCompleted = pythonRagClient.deleteDocVersionVectors(task, task.getPreviousVersionNo());
        if (!cleanupCompleted) {
            String message = "RAG 旧版本向量清理失败，等待调度重试";
            vectorIndexTaskService.markCleanupPending(task.getId(), message);
            log.warn("RAG 旧版本向量清理失败，已进入可重试状态 taskId={} docId={} versionNo={}",
                    task.getId(), task.getTargetId(), task.getPreviousVersionNo());
        }
    }

    private void retryOldVectorCleanup(VectorIndexTask task) {
        if (task.getPreviousVersionNo() == null
                || Objects.equals(task.getPreviousVersionNo(), task.getKnowledgeVersionNo())) {
            vectorIndexTaskService.markCompleted(task.getId());
            return;
        }

        boolean cleanupCompleted = pythonRagClient.deleteDocVersionVectors(task, task.getPreviousVersionNo());
        if (!cleanupCompleted) {
            vectorIndexTaskService.markCleanupPending(task.getId(), "RAG 旧版本向量清理仍然失败，等待下次调度");
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(transactionStatus ->
                    vectorIndexTaskService.markCompleted(task.getId()));
        } catch (RuntimeException e) {
            // 发布已经提交，清理重试阶段的数据库异常也不能把任务降级为 FAILED；
            // 保留 CLEANUP_PENDING，等待下一轮调度继续补偿。
            vectorIndexTaskService.markCleanupPending(task.getId(),
                    "旧版本向量已清理，但任务完成状态写入失败，等待下次调度");
            log.warn("旧版本向量清理成功但任务状态更新失败 taskId={}，保留可重试状态", task.getId(), e);
        }
    }

    private void cleanupCurrentVectorsAfterPublishFailure(VectorIndexTask task) {
        if (task.getKnowledgeVersionNo() == null) {
            return;
        }
        boolean cleanupCompleted = pythonRagClient.deleteDocVersionVectors(task, task.getKnowledgeVersionNo());
        if (!cleanupCompleted) {
            log.error("RAG 发布事务失败后新版本向量补偿清理也失败 taskId={} docId={} versionNo={}",
                    task.getId(), task.getTargetId(), task.getKnowledgeVersionNo());
        }
    }

    private KnowledgeDocVersion requireVersion(VectorIndexTask task) {
        KnowledgeDocVersion version = knowledgeDocVersionMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocVersion>()
                        .eq(KnowledgeDocVersion::getDocId, task.getTargetId())
                        .eq(KnowledgeDocVersion::getVersionNo, task.getKnowledgeVersionNo()));
        if (version == null) {
            throw new BusinessException("文档版本不存在，无法向量化");
        }
        if (!StringUtils.hasText(version.getContent())) {
            throw new BusinessException("文档版本内容为空，无法向量化");
        }
        return version;
    }

    private List<KnowledgeChunk> chunkAndSave(VectorIndexTask task, String content) {
        List<Map<String, Object>> chunkPayloads = pythonRagClient.chunkDocument(task, content);
        if (chunkPayloads.isEmpty()) {
            throw new BusinessException("Python 未返回可向量化的 RAG 切片");
        }

        knowledgeChunkMapper.delete(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getDocId, task.getTargetId())
                        .eq(KnowledgeChunk::getVersionNo, task.getKnowledgeVersionNo()));

        Map<String, Long> entityIds = loadEntityIds(task.getDatasourceId());
        List<KnowledgeChunk> chunks = IntStream.range(0, chunkPayloads.size())
                .mapToObj(index -> toKnowledgeChunk(task, chunkPayloads.get(index), index, entityIds))
                .toList();
        knowledgeChunkService.saveBatch(chunks);
        return chunks;
    }

    private KnowledgeChunk toKnowledgeChunk(
            VectorIndexTask task,
            Map<String, Object> payload,
            int fallbackChunkIndex,
            Map<String, Long> entityIds) {
        return KnowledgeChunk.builder()
                .docId(task.getTargetId())
                .versionNo(task.getKnowledgeVersionNo())
                .chunkIndex(toNullableInt(payload.get("chunkIndex"), fallbackChunkIndex))
                .chunkGroupId(text(payload, "chunkGroupId", "chunk_group_id"))
                .metadataSnapshotId(task.getMetadataSnapshotId())
                .chunkType(text(payload, "chunkType", "chunk_type"))
                .chunkText(text(payload, "chunkText", "chunk_text"))
                .relatedTable(text(payload, "tableName", "relatedTable", "related_table"))
                .relatedColumn(text(payload, "relatedColumn", "related_column"))
                .relatedTables(jsonText(payload, "relatedTables", "related_tables"))
                .relatedColumns(jsonText(payload, "relatedColumns", "related_columns"))
                .entityIds(entityIdsJson(payload, entityIds))
                .trustScore(toNullableInt(payload.get("trustScore"), null))
                .contentHash(text(payload, "contentHash", "content_hash"))
                .reviewStatus(defaultText(payload, ReviewStatus.APPROVED.name(), "reviewStatus", "review_status"))
                .vectorStatus("PENDING")
                .build();
    }

    private void markChunksIndexed(VectorIndexTask task) {
        knowledgeChunkMapper.update(null,
                new UpdateWrapper<KnowledgeChunk>()
                        .eq("doc_id", task.getTargetId())
                        .eq("version_no", task.getKnowledgeVersionNo())
                        .set("vector_status", "INDEXED"));
        if (task.getPreviousVersionNo() != null
                && !Objects.equals(task.getPreviousVersionNo(), task.getKnowledgeVersionNo())) {
            knowledgeChunkMapper.update(null,
                    new UpdateWrapper<KnowledgeChunk>()
                            .eq("doc_id", task.getTargetId())
                            .eq("version_no", task.getPreviousVersionNo())
                            .set("vector_status", "SUPERSEDED"));
        }
    }

    private void markDocumentPublished(VectorIndexTask task) {
        KnowledgeDoc doc = knowledgeDocMapper.selectById(task.getTargetId());
        if (doc == null) {
            throw new BusinessException("文档不存在，无法标记发布成功");
        }
        doc.setStatus(DocStatus.PUBLISHED.name());
        doc.setReviewStatus(ReviewStatus.APPROVED.name());
        knowledgeDocMapper.updateById(doc);
    }

    private void restoreDocAfterFailure(VectorIndexTask task) {
        if (task.getTargetId() == null) {
            return;
        }
        KnowledgeDoc doc = knowledgeDocMapper.selectById(task.getTargetId());
        if (doc == null || !DocStatus.INDEXING.name().equals(doc.getStatus())) {
            return;
        }
        doc.setStatus(DocStatus.APPROVED.name());
        knowledgeDocMapper.updateById(doc);
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                log.warn("Python 返回的向量化数量无法解析为整数: {}", text);
            }
        }
        return 0;
    }

    private String text(Map<String, Object> payload, String... keys) {
        return defaultText(payload, "", keys);
    }

    private String defaultText(Map<String, Object> payload, String defaultValue, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return defaultValue;
    }

    /**
     * Python chunker 只负责文本语义，实体 ID 由 Java 元数据图谱补齐，避免
     * Python 反向持有 Java 的元数据事实。一次任务只查询一次，同时建立表级和
     * 列级索引，使 FOREIGN_KEY（列到列）关系也能被关系增强读取。
     */
    private Map<String, Long> loadEntityIds(Long datasourceId) {
        if (datasourceId == null || metadataEntityService == null) {
            return Map.of();
        }
        try {
            Map<String, Long> result = new HashMap<>();
            List<MetadataEntity> entities = metadataEntityService.getByDatasourceId(datasourceId);
            if (entities != null) {
                for (MetadataEntity entity : entities) {
                    if (entity.getId() == null) {
                        continue;
                    }
                    if (MetadataEntity.TYPE_TABLE.equals(entity.getEntityType())
                            && StringUtils.hasText(entity.getName())) {
                        result.putIfAbsent("table:" + entity.getName().toLowerCase(Locale.ROOT), entity.getId());
                    } else if (MetadataEntity.TYPE_COLUMN.equals(entity.getEntityType())
                            && StringUtils.hasText(entity.getFqn())) {
                        String[] parts = entity.getFqn().toLowerCase(Locale.ROOT).split("\\.");
                        if (parts.length >= 2) {
                            String tableAndColumn = parts[parts.length - 2] + "." + parts[parts.length - 1];
                            result.putIfAbsent("column:" + tableAndColumn, entity.getId());
                        }
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("加载 RAG 表实体 ID 失败 datasourceId={}，继续使用无关系增强的 chunk", datasourceId, e);
            return Map.of();
        }
    }

    private String entityIdsJson(Map<String, Object> payload, Map<String, Long> entityIds) {
        String existing = jsonText(payload, "entityIds", "entity_ids");
        List<Long> resolved = new ArrayList<>();
        for (String id : stringList(existing)) {
            try {
                Long parsed = Long.valueOf(id);
                if (!resolved.contains(parsed)) {
                    resolved.add(parsed);
                }
            } catch (NumberFormatException ignored) {
                // 忽略旧客户端中无法解析的实体 ID。
            }
        }

        List<String> relatedTables = stringList(payload.get("relatedTables"), payload.get("related_tables"));
        for (String table : stringList(payload.get("relatedTables"), payload.get("related_tables"))) {
            Long entityId = entityIds.get("table:" + table.toLowerCase(Locale.ROOT));
            if (entityId != null && !resolved.contains(entityId)) {
                resolved.add(entityId);
            }
        }
        String fallbackTable = text(payload, "tableName", "relatedTable", "related_table");
        if (relatedTables.isEmpty() && StringUtils.hasText(fallbackTable)) {
            relatedTables = List.of(fallbackTable);
            Long entityId = entityIds.get("table:" + fallbackTable.toLowerCase(Locale.ROOT));
            if (entityId != null && !resolved.contains(entityId)) {
                resolved.add(entityId);
            }
        }

        for (String column : stringList(payload.get("relatedColumns"), payload.get("related_columns"))) {
            String normalizedColumn = column.toLowerCase(Locale.ROOT).trim();
            if (!normalizedColumn.contains(".")) {
                for (String table : relatedTables) {
                    Long entityId = entityIds.get("column:" + table.toLowerCase(Locale.ROOT) + "." + normalizedColumn);
                    if (entityId != null && !resolved.contains(entityId)) {
                        resolved.add(entityId);
                        break;
                    }
                }
            } else {
                Long entityId = entityIds.get("column:" + normalizedColumn);
                if (entityId != null && !resolved.contains(entityId)) {
                    resolved.add(entityId);
                }
            }
        }

        if (resolved.isEmpty()) {
            return existing;
        }
        try {
            return JSON_MAPPER.writeValueAsString(resolved);
        } catch (Exception e) {
            log.warn("RAG chunk 实体 ID 序列化失败", e);
            return existing;
        }
    }

    private List<String> stringList(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof Collection<?> collection) {
                return collection.stream().map(String::valueOf).filter(StringUtils::hasText).toList();
            }
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                try {
                    var node = JSON_MAPPER.readTree(text);
                    if (node != null && node.isArray()) {
                        List<String> result = new ArrayList<>();
                        node.forEach(item -> {
                            if (item.isValueNode() && !item.isNull() && StringUtils.hasText(item.asText())) {
                                result.add(item.asText());
                            }
                        });
                        return result;
                    }
                } catch (Exception ignored) {
                    // 兼容旧客户端的逗号分隔格式。
                }
                return List.of(text.split(","));
            }
        }
        return List.of();
    }

    private Integer toNullableInt(Object value, Integer defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException e) {
                log.warn("Python 返回的整数无法解析: {}", text);
            }
        }
        return defaultValue;
    }

    private String jsonText(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof String text) {
                return text;
            }
            try {
                return JSON_MAPPER.writeValueAsString(value);
            } catch (Exception e) {
                log.warn("切片 metadata JSON 序列化失败 key={}", key, e);
                return null;
            }
        }
        return null;
    }
}
