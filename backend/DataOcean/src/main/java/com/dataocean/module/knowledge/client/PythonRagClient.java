package com.dataocean.module.knowledge.client;

import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import com.dataocean.module.knowledge.entity.VectorIndexTask;

import java.util.List;
import java.util.Map;

/**
 * Python RAG 服务客户端接口。
 */
public interface PythonRagClient {

    /**
     * 调用 Python RAG 服务执行知识切片向量化。
     *
     * @param task          向量化任务
     * @param chunks        待写入的知识切片
     * @param forceRebuild  是否先清理同一文档的旧向量后再写入
     * @return Python 服务响应
     */
    Map<String, Object> vectorize(VectorIndexTask task, List<KnowledgeChunk> chunks, boolean forceRebuild);

    /**
     * 调用 Python RAG 服务切割 skills.md 文档。
     *
     * @param task    向量化任务上下文
     * @param content 完整 skills.md 内容
     * @return Python 返回的 chunk 清单
     */
    List<Map<String, Object>> chunkDocument(VectorIndexTask task, String content);

    /**
     * 清理已经被新版本替换的 Milvus 旧向量。
     *
     * @param task       当前已成功生效的任务
     * @param versionNo  待清理的旧版本号
     */
    void deleteDocVersionVectors(VectorIndexTask task, Integer versionNo);
}
