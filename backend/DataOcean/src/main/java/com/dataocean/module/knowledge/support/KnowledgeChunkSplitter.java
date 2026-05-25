package com.dataocean.module.knowledge.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import com.dataocean.module.knowledge.enums.ChunkType;
import com.dataocean.module.knowledge.enums.ReviewStatus;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识文档切片工具类。
 * <p>
 * 按 Markdown 二级标题拆分文档内容为独立切片，根据内容关键词推断切片类型。
 * 供发布、回滚等场景统一调用，避免切片逻辑重复。
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KnowledgeChunkSplitter {

    private final KnowledgeChunkMapper knowledgeChunkMapper;

    /**
     * 切分文档内容并持久化切片。
     * <p>
     * 先删除同文档同版本的旧切片，再按二级标题拆分生成新切片。
     * </p>
     *
     * @param docId              文档 ID
     * @param versionNo          版本号
     * @param metadataSnapshotId 元数据快照 ID
     * @param content            文档 Markdown 内容
     * @return 生成的切片列表
     */
    public List<KnowledgeChunk> splitAndSave(Long docId, Integer versionNo, Long metadataSnapshotId, String content) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException("文档内容为空，无法切分为 RAG 知识片段");
        }

        // 先删除同文档同版本的旧切片
        knowledgeChunkMapper.delete(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getDocId, docId)
                        .eq(KnowledgeChunk::getVersionNo, versionNo));

        // 按 Markdown 二级标题拆分（(?m) 启用多行模式使 ^ 匹配行首）
        String[] sections = content.split("(?m)(?=^## )", -1);
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            chunks.add(KnowledgeChunk.builder()
                    .docId(docId)
                    .versionNo(versionNo)
                    .metadataSnapshotId(metadataSnapshotId)
                    .chunkType(inferChunkType(trimmed))
                    .chunkText(trimmed)
                    .reviewStatus(ReviewStatus.APPROVED.name())
                    .vectorStatus("PENDING")
                    .build());
        }

        if (chunks.isEmpty()) {
            throw new BusinessException("文档内容无法切分为 RAG 知识片段");
        }

        for (KnowledgeChunk chunk : chunks) {
            knowledgeChunkMapper.insert(chunk);
        }
        log.info("文档切片完成 docId={} versionNo={} chunkCount={}", docId, versionNo, chunks.size());
        return chunks;
    }

    /**
     * 预览切片结果，不写入数据库。
     *
     * @param content 文档 Markdown 内容
     * @return 切片文本和类型列表
     */
    public List<Map<String, String>> preview(String content) {
        List<Map<String, String>> result = new ArrayList<>();
        if (!StringUtils.hasText(content)) {
            return result;
        }
        String[] sections = content.split("(?m)(?=^## )", -1);
        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            result.add(Map.of("chunk_text", trimmed, "chunk_type", inferChunkType(trimmed)));
        }
        return result;
    }

    /**
     * 根据切片内容推断切片类型。
     */
    public String inferChunkType(String chunkText) {
        String lowerText = chunkText.toLowerCase();
        if (lowerText.contains("join") || lowerText.contains("关联")) {
            return ChunkType.JOIN_PATH.name();
        }
        if (lowerText.contains("指标") || lowerText.contains("metric")) {
            return ChunkType.METRIC.name();
        }
        if (lowerText.contains("防坑") || lowerText.contains("注意")) {
            return ChunkType.FIELD_NOTE.name();
        }
        return ChunkType.TABLE_DESC.name();
    }
}
