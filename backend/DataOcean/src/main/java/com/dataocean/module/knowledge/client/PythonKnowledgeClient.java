package com.dataocean.module.knowledge.client;

import java.util.List;
import java.util.Map;

/**
 * Python 知识库服务客户端接口
 * <p>
 * 定义调用 Python AI 服务生成 skills.md 草稿的方法。
 * </p>
 *
 * @author dataocean
 */
public interface PythonKnowledgeClient {

    /**
     * 调用 Python 服务生成 skills.md 草稿
     *
     * @param snapshotId     元数据快照 ID
     * @param datasourceId   数据源 ID
     * @param tablesMetadata 表元数据列表
     * @param foreignKeys    外键关系列表
     * @param indexes        索引信息列表
     * @return 生成结果 Map，包含 content、generation_source、warnings
     */
    Map<String, Object> generateDraft(Long snapshotId, Long datasourceId,
                                       List<Map<String, Object>> tablesMetadata,
                                       List<Map<String, Object>> foreignKeys,
                                       List<Map<String, Object>> indexes);
}
