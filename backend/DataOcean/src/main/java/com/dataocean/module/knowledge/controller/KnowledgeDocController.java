package com.dataocean.module.knowledge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.knowledge.dto.*;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.service.KnowledgeDocService;
import com.dataocean.module.knowledge.service.KnowledgeVersionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识文档管理控制器
 * <p>
 * 提供 skills.md 文档的 CRUD、审核流程、AI 草稿生成、版本管理等 REST API 端点。
 * 所有接口需要 knowledge:manage 权限。
 * </p>
 */
@RestController
@RequestMapping("/api/admin/knowledge-docs")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('knowledge:manage')")
@Slf4j
public class KnowledgeDocController {

    private final KnowledgeDocService knowledgeDocService;
    private final KnowledgeVersionService knowledgeVersionService;

    // === 文档 CRUD ===

    /**
     * 分页查询知识文档列表
     *
     * @param datasourceId 数据源 ID（可选筛选条件）
     * @param status       文档状态（可选筛选条件）
     * @param page         页码
     * @param pageSize     每页条数
     * @return 分页文档列表
     */
    @GetMapping
    public Result<Page<KnowledgeDoc>> listDocs(
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        log.debug("收到知识文档列表查询请求 datasourceId={} status={}", datasourceId, status);
        return Result.success(knowledgeDocService.listDocs(datasourceId, status, page, pageSize));
    }

    /**
     * 获取文档详情
     *
     * @param id 文档 ID
     * @return 文档详情
     */
    @GetMapping("/{id}")
    public Result<KnowledgeDoc> getDoc(@PathVariable Long id) {
        return Result.success(knowledgeDocService.getDocById(id));
    }

    /**
     * 创建知识文档
     *
     * @param request 创建请求参数
     * @return 新文档 ID
     */
    @PostMapping
    public Result<Map<String, Long>> createDoc(@Valid @RequestBody KnowledgeDocCreateDTO request) {
        log.debug("收到创建知识文档请求 datasourceId={} title={}", request.getDatasourceId(), request.getTitle());
        Long id = knowledgeDocService.createDoc(request.getDatasourceId(), request.getTitle(), request.getContent());
        return Result.success("创建成功", Map.of("id", id));
    }

    /**
     * 编辑知识文档
     *
     * @param id      文档 ID
     * @param request 编辑请求参数
     * @return 操作结果
     */
    @PutMapping("/{id}")
    public Result<Void> updateDoc(@PathVariable Long id, @Valid @RequestBody KnowledgeDocUpdateDTO request) {
        log.debug("收到编辑知识文档请求 docId={} version={}", id, request.getVersion());
        knowledgeDocService.updateDoc(id, request.getTitle(), request.getContent(), request.getVersion());
        return Result.success("更新成功", null);
    }

    // === 审核流程 ===

    /**
     * 提交审核
     *
     * @param id 文档 ID
     * @return 操作结果
     */
    @PostMapping("/{id}/submit-review")
    public Result<Void> submitReview(@PathVariable Long id) {
        log.debug("收到提交审核请求 docId={}", id);
        knowledgeDocService.submitReview(id);
        return Result.success("已提交审核", null);
    }

    /**
     * 审核通过
     *
     * @param id      文档 ID
     * @param request 审核意见（可选）
     * @return 操作结果
     */
    @PostMapping("/{id}/approve")
    public Result<Void> approve(@PathVariable Long id, @RequestBody(required = false) ReviewRequestDTO request) {
        log.debug("收到审核通过请求 docId={}", id);
        knowledgeDocService.approve(id, request == null ? null : request.getComment());
        return Result.success("审核通过", null);
    }

    /**
     * 审核拒绝
     *
     * @param id      文档 ID
     * @param request 拒绝原因
     * @return 操作结果
     */
    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @Valid @RequestBody ReviewRequestDTO request) {
        log.debug("收到审核拒绝请求 docId={}", id);
        knowledgeDocService.reject(id, request.getComment());
        return Result.success("已驳回", null);
    }

    /**
     * 发布文档
     *
     * @param id 文档 ID
     * @return 操作结果
     */
    @PostMapping("/{id}/publish")
    public Result<Void> publish(@PathVariable Long id) {
        log.debug("收到发布文档请求 docId={}", id);
        knowledgeDocService.publish(id);
        return Result.success("发布成功", null);
    }

    // === AI 草稿生成 ===

    /**
     * 生成 AI 草稿
     *
     * @param id      文档 ID
     * @param request 草稿生成请求参数
     * @return 生成的草稿内容
     */
    @PostMapping("/{id}/generate-draft")
    public Result<Map<String, String>> generateDraft(@PathVariable Long id, @Valid @RequestBody GenerateDraftDTO request) {
        log.debug("收到生成草稿请求 docId={} snapshotId={}", id, request.getSnapshotId());
        String content = knowledgeDocService.generateDraft(id, request.getSnapshotId());
        return Result.success("草稿生成成功", Map.of("content", content));
    }

    // === 版本管理 ===

    /**
     * 查询文档版本列表
     *
     * @param id 文档 ID
     * @return 版本列表
     */
    @GetMapping("/{id}/versions")
    public Result<List<KnowledgeDocVersion>> listVersions(@PathVariable Long id) {
        return Result.success(knowledgeVersionService.listVersions(id));
    }

    /**
     * 获取指定版本详情
     *
     * @param id        文档 ID
     * @param versionNo 版本号
     * @return 版本详情
     */
    @GetMapping("/{id}/versions/{versionNo}")
    public Result<KnowledgeDocVersion> getVersion(@PathVariable Long id, @PathVariable Integer versionNo) {
        return Result.success(knowledgeVersionService.getVersion(id, versionNo));
    }

    /**
     * 回滚到指定版本
     *
     * @param id      文档 ID
     * @param request 回滚请求参数
     * @return 新版本号
     */
    @PostMapping("/{id}/rollback")
    public Result<Map<String, Integer>> rollback(@PathVariable Long id, @Valid @RequestBody RollbackDTO request) {
        log.debug("收到版本回滚请求 docId={} targetVersionNo={}", id, request.getTargetVersionNo());
        Integer newVersionNo = knowledgeVersionService.rollback(id, request.getTargetVersionNo());
        return Result.success("回滚成功", Map.of("newVersionNo", newVersionNo));
    }

    // === RAG 预览 ===

    /**
     * 预览文档切片结果
     * <p>
     * 模拟发布时的切片逻辑，返回当前内容会被切成哪些 chunks，
     * 供作者在发布前预览 RAG 检索效果。
     * </p>
     *
     * @param id 文档 ID
     * @return 切片预览列表
     */
    @PostMapping("/{id}/preview-chunks")
    public Result<List<Map<String, String>>> previewChunks(@PathVariable Long id) {
        log.debug("收到切片预览请求 docId={}", id);
        List<Map<String, String>> chunks = knowledgeDocService.previewChunks(id);
        return Result.success(chunks);
    }
}
