package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.util.EntityChecker;
import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识文档共享辅助工具类。
 * <p>
 * 提供文档和版本的通用查询方法，供拆分后的多个 Service 共同使用。
 * </p>
 *
 * @author DataOcean
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocHelper {

    private final KnowledgeDocMapper knowledgeDocMapper;
    private final KnowledgeDocVersionMapper knowledgeDocVersionMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;

    /**
     * 根据 ID 查询文档，不存在则抛出业务异常。
     *
     * @param id 文档 ID
     * @return 文档实体
     * @throws BusinessException 文档不存在时抛出
     */
    public KnowledgeDoc requireDoc(Long id) {
        return EntityChecker.require(knowledgeDocMapper, id, "知识文档");
    }

    /**
     * 根据文档 ID 和版本号查询版本记录，不存在或缺少快照则抛出业务异常。
     *
     * @param docId     文档 ID
     * @param versionNo 版本号
     * @return 版本实体
     * @throws BusinessException 版本不存在或缺少元数据快照时抛出
     */
    public KnowledgeDocVersion requireVersion(Long docId, Integer versionNo) {
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
    public Integer findCurrentPublishedVersionNo(Long docId) {
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
}
