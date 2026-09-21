package com.dataocean.module.knowledge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.system.aspect.AdminAuditLog;
import com.dataocean.module.knowledge.dto.*;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.entity.VectorIndexTask;
import com.dataocean.module.knowledge.service.KnowledgeVersionService;
import com.dataocean.module.knowledge.service.VectorIndexTaskService;
import com.dataocean.module.knowledge.service.impl.KnowledgeDocCrudService;
import com.dataocean.module.knowledge.service.impl.KnowledgeDocLifecycleService;
import com.dataocean.module.knowledge.service.impl.KnowledgeDocPublishService;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.s1.annotation.IamS1Resource;
import com.dataocean.module.permission.s1.annotation.IamS1ScopedList;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识文档管理控制器
 * <p>
 * 提供 skills.md 文档的 CRUD、审核流程、AI 草稿生成、版本管理等 REST API 端点。
 * </p>
 *
 * <p>准入使用 IAM-SIMPLE-1 方法级注解，按 B0 冻结拆成四个功能码：</p>
 * <ul>
 *   <li>{@code knowledge:view} —— 列表、详情、审核记录、来源快照、版本、版本详情、版本差异、
 *       索引任务、切分预览（{@code preview-chunks} 是 POST，但 B0 冻结为**只读预览**，
 *       不因为 HTTP 方法是 POST 就提升成 manage）；</li>
 *   <li>{@code knowledge:manage} —— 新建、编辑、提交审核、生成草稿、按快照批量生成；</li>
 *   <li>{@code knowledge:approve} —— 审核通过/驳回；</li>
 *   <li>{@code knowledge:publish} —— 发布与回滚。</li>
 * </ul>
 *
 * <p>文档类端点的归属由 {@link IamS1ResourceType#KNOWLEDGE_DOCUMENT} 解析器复核
 * （文档 → 数据源，并校验当前版本与来源快照归属一致）；列表由 Service
 * 把负责源**下推到 SQL**，空范围返回空页。</p>
 *
 * <p>职责拆分后，Controller 委托给三个独立 Service：
 * <ul>
 *   <li>{@link KnowledgeDocCrudService} — 文档 CRUD</li>
 *   <li>{@link KnowledgeDocLifecycleService} — 状态流转（审核、发布）</li>
 *   <li>{@link KnowledgeDocPublishService} — AI 生成、切片预览</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/admin/knowledge-docs")
@RequiredArgsConstructor
@Slf4j
@AdminAuditLog
public class KnowledgeDocController {

    /** 查看文档、版本、审核记录、切分与索引状态。 */
    private static final String VIEW_FUNCTION = "knowledge:view";
    /** 新建、编辑、提交审核与生成草稿。 */
    private static final String MANAGE_FUNCTION = "knowledge:manage";
    /** 审核通过/驳回：独立功能码，不自动带来维护或发布权。 */
    private static final String APPROVE_FUNCTION = "knowledge:approve";
    /** 发布与回滚：独立功能码。 */
    private static final String PUBLISH_FUNCTION = "knowledge:publish";

    private final KnowledgeDocCrudService crudService;
    private final KnowledgeDocLifecycleService lifecycleService;
    private final KnowledgeDocPublishService publishService;
    private final KnowledgeVersionService knowledgeVersionService;
    private final VectorIndexTaskService vectorIndexTaskService;
    private final IamS1CapabilityService capabilityService;

    /** 调用者在指定功能上负责的数据源 ID；空列表表示没有任何负责源。 */
    private List<Long> visibleDatasourceIds(Long userId, String functionCode) {
        return capabilityService.responsibleDatasourcesWithFunction(userId, functionCode)
                .stream()
                .map(IamS1DatasourceRefVO::id)
                .toList();
    }

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
    @IamS1ScopedList(VIEW_FUNCTION)
    public Result<Page<KnowledgeDoc>> listDocs(
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        log.debug("收到知识文档列表查询请求 datasourceId={} status={}", datasourceId, status);
        // 可见范围下推 SQL；空负责源返回空页，调用方显式筛选无权数据源时直接 403。
        List<Long> visible = visibleDatasourceIds(UserContext.currentUserId(), VIEW_FUNCTION);
        return Result.success(crudService.listDocsInDatasources(visible, datasourceId, status, page, pageSize));
    }

    /**
     * 获取文档详情
     *
     * @param id 文档 ID
     * @return 文档详情
     */
    @GetMapping("/{id}")
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.KNOWLEDGE_DOCUMENT, resourceIds = "#id")
    public Result<KnowledgeDoc> getDoc(@PathVariable Long id) {
        return Result.success(crudService.getDocById(id));
    }

    /**
     * 创建知识文档
     *
     * @param request 创建请求参数
     * @return 新文档 ID
     */
    @PostMapping
    @IamS1Resource(function = MANAGE_FUNCTION, resourceType = IamS1ResourceType.DATASOURCE, resourceIds = "#request.datasourceId")
    public Result<Map<String, Long>> createDoc(@Valid @RequestBody KnowledgeDocCreateDTO request) {
        log.debug("收到创建知识文档请求 datasourceId={} title={}", request.getDatasourceId(), request.getTitle());
        Long id = crudService.createDoc(request.getDatasourceId(), request.getTitle(), request.getContent());
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
    @IamS1Resource(function = MANAGE_FUNCTION, resourceType = IamS1ResourceType.KNOWLEDGE_DOCUMENT, resourceIds = "#id")
    public Result<Void> updateDoc(@PathVariable Long id, @Valid @RequestBody KnowledgeDocUpdateDTO request) {
        log.debug("收到编辑知识文档请求 docId={} version={}", id, request.getVersion());
        crudService.updateDoc(
                id,
                request.getTitle(),
                request.getContent(),
                request.getVersion(),
                request.getChangeSummary());
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
    @IamS1Resource(function = MANAGE_FUNCTION, resourceType = IamS1ResourceType.KNOWLEDGE_DOCUMENT, resourceIds = "#id")
    public Result<Void> submitReview(@PathVariable Long id) {
        log.debug("收到提交审核请求 docId={}", id);
        lifecycleService.submitReview(id);
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
    @IamS1Resource(function = APPROVE_FUNCTION, resourceType = IamS1ResourceType.KNOWLEDGE_DOCUMENT, resourceIds = "#id")
    public Result<Void> approve(@PathVariable Long id, @RequestBody(required = false) ReviewRequestDTO request) {
        log.debug("收到审核通过请求 docId={}", id);
        lifecycleService.approve(id, request == null ? null : request.getComment());
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
    @IamS1Resource(function = APPROVE_FUNCTION, resourceType = IamS1ResourceType.KNOWLEDGE_DOCUMENT, resourceIds = "#id")
    public Result<Void> reject(@PathVariable Long id, @Valid @RequestBody ReviewRequestDTO request) {
        log.debug("收到审核拒绝请求 docId={}", id);
        lifecycleService.reject(id, request.getComment());
        return Result.success("已驳回", null);
    }

    /**
     * 发布文档
     *
     * @param id 文档 ID
     * @return 操作结果
     */
    @PostMapping("/{id}/publish")
    @IamS1Resource(function = PUBLISH_FUNCTION, resourceType = IamS1ResourceType.KNOWLEDGE_DOCUMENT, resourceIds = "#id")
    public Result<Void> publish(@PathVariable Long id) {
        log.debug("收到发布文档请求 docId={}", id);
        lifecycleService.publish(id);
        return Result.success("发布成功", null);
    }

    // === AI 草稿生成 ===

    /**
     * 生成 AI 草稿（单文档模式）
     *
     * @param id      文档 ID
     * @param request 草稿生成请求参数
     * @return 生成的草稿内容
     */
    @PostMapping("/{id}/generate-draft")
    @IamS1Resource(function = MANAGE_FUNCTION, resourceType = IamS1ResourceType.KNOWLEDGE_DOCUMENT, resourceIds = "#id")
    public Result<Map<String, String>> generateDraft(@PathVariable Long id, @Valid @RequestBody GenerateDraftDTO request) {
        log.debug("收到生成草稿请求 docId={} snapshotId={}", id, request.getSnapshotId());
        String content = publishService.generateDraft(id, request.getSnapshotId());
        return Result.success("草稿生成成功", Map.of("content", content));
    }

    /**
     * AI 一键生成（自动分析业务域，批量创建文档）
     * <p>
     * 用户选择一个元数据快照，AI 分析表结构识别业务域，
     * 每个域自动创建一份独立的 skills.md 文档（DRAFT 状态）。
     * </p>
     *
     * @param datasourceId 数据源 ID
     * @param request      批量生成请求参数（包含 snapshotId）
     * @return 创建的文档列表
     */
    @PostMapping("/generate-from-snapshot")
    @IamS1Resource(function = MANAGE_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#request.snapshotId")
    public Result<List<Map<String, Object>>> generateFromSnapshot(
            @RequestParam Long datasourceId,
            @Valid @RequestBody BatchGenerateDTO request) {
        log.info("收到 AI 一键生成请求 datasourceId={} snapshotId={}", datasourceId, request.getSnapshotId());
        List<Map<String, Object>> docs = publishService.batchGenerateFromSnapshot(datasourceId, request.getSnapshotId());
        return Result.success("AI 生成成功", docs);
    }

    /**
     * 查询文档的审核记录。
     * <p>
     * 审核意见已落库在 `knowledge_review_task`，此前全项目没有任何 Controller 暴露它，
     * 导致作者被驳回后看不到原因，无法满足开发指导 §16.3「审核拒绝后能够返回编辑并看到原因」。
     * 本接口是该表的对外读取入口：返回审核人、审核时间与审核意见，按最新在前排序。
     * </p>
     *
     * @param id 文档 ID
     * @return 审核记录列表
     */
    @GetMapping("/{id}/review-tasks")
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.KNOWLEDGE_DOCUMENT, resourceIds = "#id")
    public Result<List<KnowledgeReviewRecordVO>> listReviewRecords(@PathVariable Long id) {
        return Result.success(knowledgeVersionService.listReviewRecords(id));
    }

    /**
     * 查询文档各版本的来源快照。
     * <p>
     * 来源快照记录在各版本的 `metadata_snapshot_id` 上。此前前端要么只能显示裸 ID，
     * 要么需要「取版本列表 + 取数据源快照列表」两次请求再自行关联，且当引用的快照不在
     * 已加载的分页范围内时关联不上。本接口把该关联在服务端一次完成。
     * </p>
     *
     * @param id 文档 ID
     * @return 来源快照列表，按版本号降序
     */
    @GetMapping("/{id}/source-snapshots")
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.KNOWLEDGE_DOCUMENT, resourceIds = "#id")
    public Result<List<KnowledgeSourceSnapshotVO>> listSourceSnapshots(@PathVariable Long id) {
        return Result.success(knowledgeVersionService.listSourceSnapshots(id));
    }

    // === 版本管理 ===

    /**
     * 查询文档版本列表
     *
     * @param id 文档 ID
     * @return 版本列表
     */
    @GetMapping("/{id}/versions")
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.KNOWLEDGE_DOCUMENT, resourceIds = "#id")
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
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.KNOWLEDGE_DOCUMENT, resourceIds = "#id")
    public Result<KnowledgeDocVersion> getVersion(@PathVariable Long id, @PathVariable Integer versionNo) {
        return Result.success(knowledgeVersionService.getVersion(id, versionNo));
    }

    /**
     * 版本对比（行级差异）
     *
     * @param id 文档 ID
     * @param v1 版本号 1
     * @param v2 版本号 2
     * @return 行级差异列表
     */
    @GetMapping("/{id}/versions/diff")
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.KNOWLEDGE_DOCUMENT, resourceIds = "#id")
    public Result<List<Map<String, Object>>> diffVersions(@PathVariable Long id,
                                                           @RequestParam Integer v1,
                                                           @RequestParam Integer v2) {
        log.debug("收到版本对比请求 docId={} v1={} v2={}", id, v1, v2);
        return Result.success(knowledgeVersionService.diffVersions(id, v1, v2));
    }

    /**
     * 回滚到指定版本
     *
     * @param id      文档 ID
     * @param request 回滚请求参数
     * @return 新版本号
     */
    @PostMapping("/{id}/rollback")
    @IamS1Resource(function = PUBLISH_FUNCTION, resourceType = IamS1ResourceType.KNOWLEDGE_DOCUMENT, resourceIds = "#id")
    public Result<Map<String, Integer>> rollback(@PathVariable Long id, @Valid @RequestBody RollbackDTO request) {
        log.debug("收到版本回滚请求 docId={} targetVersionNo={}", id, request.getTargetVersionNo());
        Integer newVersionNo = knowledgeVersionService.rollback(id, request.getTargetVersionNo());
        return Result.success("回滚成功", Map.of("newVersionNo", newVersionNo));
    }

    /**
     * 查询文档的向量化任务。
     * <p>
     * `vector_index_task` 此前只被 knowledge 模块内部引用，没有任何 Controller 暴露它，
     * 导致文档处于 `INDEXING` 时前端无法显示进度与失败原因（见开发指导 §7.11
     * 「`INDEXING`：显示进度和失败信息，禁止重复发布」）。本接口补上该读取入口。
     * </p>
     *
     * @param id 文档 ID
     * @return 该文档的向量化任务列表，最新在前
     */
    @GetMapping("/{id}/vector-tasks")
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.KNOWLEDGE_DOCUMENT, resourceIds = "#id")
    public Result<List<VectorIndexTask>> listVectorTasks(@PathVariable Long id) {
        return Result.success(knowledgeVersionService.listVectorTasksOfDocument(id));
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
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.KNOWLEDGE_DOCUMENT, resourceIds = "#id")
    public Result<List<Map<String, String>>> previewChunks(@PathVariable Long id) {
        log.debug("收到切片预览请求 docId={}", id);
        List<Map<String, String>> chunks = publishService.previewChunks(id);
        return Result.success(chunks);
    }
}
