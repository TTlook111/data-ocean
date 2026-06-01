package com.dataocean.module.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;

import java.util.List;
import java.util.Map;

/**
 * 知识文档管理业务接口。
 * <p>
 * 提供文档的创建、编辑、查询、状态流转等管理功能。
 * 文档生命周期：DRAFT → PENDING_REVIEW → APPROVED → PUBLISHED。
 * </p>
 *
 * @author DataOcean
 */
public interface KnowledgeDocService {

    /**
     * 分页查询知识文档列表。
     * <p>
     * 支持按数据源 ID 和文档状态筛选，结果按创建时间降序排列。
     * </p>
     *
     * @param datasourceId 数据源 ID（可选筛选）
     * @param status       文档状态（可选筛选）
     * @param page         页码
     * @param pageSize     每页大小
     * @return 分页文档列表
     */
    Page<KnowledgeDoc> listDocs(Long datasourceId, String status, Integer page, Integer pageSize);

    /**
     * 获取文档详情。
     *
     * @param id 文档 ID
     * @return 文档实体
     */
    KnowledgeDoc getDocById(Long id);

    /**
     * 创建知识文档。
     * <p>
     * 新文档初始状态为 DRAFT，并创建 versionNo=1 的初始版本。
     * </p>
     *
     * @param datasourceId 数据源 ID
     * @param title        文档标题
     * @param content      文档内容（Markdown）
     * @return 新创建文档的 ID
     */
    Long createDoc(Long datasourceId, String title, String content);

    /**
     * 编辑知识文档（乐观锁校验）。
     * <p>
     * 前端传入版本号用于乐观锁冲突检测，更新失败时抛出业务异常提示刷新。
     * </p>
     *
     * @param id      文档 ID
     * @param title   新标题
     * @param content 新内容
     * @param version 前端传入的版本号（乐观锁）
     */
    void updateDoc(Long id, String title, String content, Integer version, String changeSummary);

    /**
     * 提交审核（DRAFT → PENDING_REVIEW）。
     *
     * @param id 文档 ID
     */
    void submitReview(Long id);

    /**
     * 审核通过（PENDING_REVIEW → APPROVED）。
     *
     * @param id      文档 ID
     * @param comment 审核意见
     */
    void approve(Long id, String comment);

    /**
     * 审核拒绝（PENDING_REVIEW → DRAFT）。
     *
     * @param id      文档 ID
     * @param comment 拒绝原因
     */
    void reject(Long id, String comment);

    /**
     * 发布文档（APPROVED → PUBLISHED）。
     * <p>
     * 发布时会创建向量化任务，将文档切片向量化进入 RAG 系统。
     * </p>
     *
     * @param id 文档 ID
     */
    void publish(Long id);

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
    String generateDraft(Long docId, Long snapshotId);

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
    List<Map<String, String>> previewChunks(Long docId);

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
    List<Map<String, Object>> batchGenerateFromSnapshot(Long datasourceId, Long snapshotId);
}
