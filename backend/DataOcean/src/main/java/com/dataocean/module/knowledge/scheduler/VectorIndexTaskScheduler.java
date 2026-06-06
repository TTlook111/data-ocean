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
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.service.VectorIndexTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        int vectorizedCount = toInt(firstPresent(response, "vectorizedCount", "successCount", "success_count"));
        if (!"COMPLETED".equals(status) || vectorizedCount != chunks.size()) {
            throw new BusinessException("RAG 向量化未完成，status=" + status + " vectorizedCount=" + vectorizedCount);
        }

        transactionTemplate.executeWithoutResult(transactionStatus -> {
            markChunksIndexed(task);
            markDocumentPublished(task);
            vectorIndexTaskService.markCompleted(task.getId());
        });

        if (task.getPreviousVersionNo() != null
                && !Objects.equals(task.getPreviousVersionNo(), task.getKnowledgeVersionNo())) {
            pythonRagClient.deleteDocVersionVectors(task, task.getPreviousVersionNo());
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

        List<KnowledgeChunk> chunks = chunkPayloads.stream()
                .map(payload -> toKnowledgeChunk(task, payload))
                .toList();
        for (KnowledgeChunk chunk : chunks) {
            knowledgeChunkMapper.insert(chunk);
        }
        return chunks;
    }

    private KnowledgeChunk toKnowledgeChunk(VectorIndexTask task, Map<String, Object> payload) {
        return KnowledgeChunk.builder()
                .docId(task.getTargetId())
                .versionNo(task.getKnowledgeVersionNo())
                .metadataSnapshotId(task.getMetadataSnapshotId())
                .chunkType(text(payload, "chunkType", "chunk_type"))
                .chunkText(text(payload, "chunkText", "chunk_text"))
                .relatedTable(text(payload, "tableName", "relatedTable", "related_table"))
                .relatedColumn(text(payload, "relatedColumn", "related_column"))
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

    private Object firstPresent(Map<String, Object> response, String... keys) {
        for (String key : keys) {
            if (response.containsKey(key)) {
                return response.get(key);
            }
        }
        return null;
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
}
