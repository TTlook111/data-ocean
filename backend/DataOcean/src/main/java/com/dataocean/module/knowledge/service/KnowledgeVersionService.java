package com.dataocean.module.knowledge.service;

import com.dataocean.module.knowledge.dto.KnowledgeReviewRecordVO;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;

import java.util.List;
import java.util.Map;

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

    /**
     * 对比两个版本的内容差异（行级 diff）。
     *
     * @param docId 文档 ID
     * @param v1    版本号 1
     * @param v2    版本号 2
     * @return 行级差异列表，每个元素包含 type（ADD/DELETE/EQUAL）和 content
     */
    List<Map<String, Object>> diffVersions(Long docId, Integer v1, Integer v2);

    /**
     * 查询文档的审核记录。
     * <p>
     * 审核任务按文档版本（`docVersionId`）关联存储，因此这里先取该文档的全部版本，
     * 再按版本 ID 查询审核任务，并解析审核人姓名。返回结果按审核任务 ID 倒序（最新在前）。
     * </p>
     *
     * @param docId 文档 ID
     * @return 审核记录列表；文档无版本或无审核记录时返回空列表
     */
    List<KnowledgeReviewRecordVO> listReviewRecords(Long docId);
}
