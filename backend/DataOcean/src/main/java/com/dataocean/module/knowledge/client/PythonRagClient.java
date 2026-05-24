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
}
