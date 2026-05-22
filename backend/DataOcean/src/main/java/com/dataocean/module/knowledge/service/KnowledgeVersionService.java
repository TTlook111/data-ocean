package com.dataocean.module.knowledge.service;

import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;

import java.util.List;

/**
 * 知识文档版本管理业务接口。
 * <p>
 * 提供版本列表、版本详情、版本创建和回滚功能。
 * 每次文档编辑或 AI 生成都会产生新版本，支持回滚到历史版本。
 * </p>
 *
 * @author DataOcean
 */
public interface KnowledgeVersionService {

    /**
     * 查询文档的版本列表。
     *
     * @param docId 文档 ID
     * @return 版本列表（按版本号降序）
     */
    List<KnowledgeDocVersion> listVersions(Long docId);

    /**
     * 获取版本详情。
     *
     * @param docId     文档 ID
     * @param versionNo 版本号
     * @return 版本实体
     */
    KnowledgeDocVersion getVersion(Long docId, Integer versionNo);

    /**
     * 创建新版本。
     * <p>
     * 自动递增版本号，同时更新文档的 currentVersion 和 content 字段。
     * </p>
     *
     * @param docId            文档 ID
     * @param content          版本内容
     * @param generationSource 生成来源
     * @param snapshotId       关联快照 ID
     * @param changeSummary    变更摘要
     * @return 新版本号
     */
    Integer createVersion(Long docId, String content, String generationSource, Long snapshotId, String changeSummary);

    /**
     * 回滚到指定版本。
     * <p>
     * 将目标版本的内容作为新版本创建（generationSource=ROLLBACK），
     * 并触发向量化任务更新 RAG 索引。
     * </p>
     *
     * @param docId           文档 ID
     * @param targetVersionNo 目标版本号
     * @return 新创建的版本号
     */
    Integer rollback(Long docId, Integer targetVersionNo);
}
