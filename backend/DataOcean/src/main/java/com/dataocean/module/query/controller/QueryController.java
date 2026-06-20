package com.dataocean.module.query.controller;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.exception.ServiceUnavailableException;
import com.dataocean.common.health.PythonHealthChecker;
import com.dataocean.common.ratelimit.RateLimitService;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.datasource.service.DatasourceAccessService;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.service.SchemaSnapshotService;
import com.dataocean.module.permission.service.DatasourcePermissionService;
import com.dataocean.module.query.client.PythonAgentClient;
import com.dataocean.module.query.entity.dto.QueryAskDTO;
import com.dataocean.module.query.entity.query.QueryHistoryQuery;
import com.dataocean.module.query.entity.vo.ConversationMessageVO;
import com.dataocean.module.query.entity.vo.QueryTaskVO;
import com.dataocean.module.query.service.ConversationService;
import com.dataocean.module.query.service.QueryTaskService;
import com.dataocean.module.audit.service.AuditLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 查询端控制器。
 * <p>
 * 提供用户端的查询提交、结果查询、任务取消和会话管理接口。
 * </p>
 */
@RestController
@RequestMapping("/api/query")
@RequiredArgsConstructor
@Slf4j
public class QueryController {

    private final QueryTaskService queryTaskService;
    private final ConversationService conversationService;
    private final PythonAgentClient pythonAgentClient;
    private final DatasourceAccessService datasourceAccessService;
    private final DatasourcePermissionService datasourcePermissionService;
    private final SchemaSnapshotService schemaSnapshotService;
    private final AuditLogService auditLogService;
    private final PythonHealthChecker pythonHealthChecker;
    private final RateLimitService rateLimitService;

    /**
     * 提交查询（异步，返回 taskId）。
     * <p>
     * 校验数据源权限 → 创建会话 → 保存用户消息 → 创建任务 → 触发 Agent。
     * </p>
     */
    @PostMapping("/ask")
    public Result<Map<String, Object>> ask(@Valid @RequestBody QueryAskDTO request) {
        Long userId = UserContext.currentUserId();
        log.info("用户提交查询 userId={} datasourceId={} question={}",
                userId, request.getDatasourceId(), request.getQuestion().substring(0, Math.min(50, request.getQuestion().length())));

        // 限流检查：每用户每分钟最多 10 次查询
        rateLimitService.checkAndRecord("rate:query:user:" + userId);

        // 前置检查：Python AI 服务是否可用
        if (!pythonHealthChecker.isAvailable()) {
            throw new ServiceUnavailableException("AI 服务暂时不可用，请稍后再试");
        }

        // 校验用户是否有权访问该数据源（多维度：用户 + 角色 + 部门）
        if (!datasourcePermissionService.checkUserAccess(userId, request.getDatasourceId())) {
            throw new BusinessException("无权访问该数据源");
        }

        // 校验数据源已发布元数据快照（前置校验，避免产生无效任务）
        MetadataSnapshot snapshot = schemaSnapshotService.getPublishedSnapshot(request.getDatasourceId());
        if (snapshot == null) {
            throw new BusinessException("该数据源尚未发布元数据快照，请先完成元数据治理");
        }

        // 获取或创建会话
        Long conversationId = conversationService.getOrCreateConversation(
                userId, request.getDatasourceId(), request.getConversationId(), request.getQuestion());

        // 保存用户消息
        conversationService.saveUserMessage(conversationId, request.getQuestion());

        // 提交查询任务
        String taskId = queryTaskService.submitQuery(userId, request.getDatasourceId(), request.getQuestion(), conversationId);

        // 异步触发 Python Agent 执行
        pythonAgentClient.executeAsync(taskId, request.getDatasourceId(), userId,
                request.getQuestion(), conversationId, snapshot.getId());

        return Result.success("查询已提交", Map.of(
                "taskId", taskId,
                "conversationId", conversationId));
    }

    /**
     * 查询任务结果（轮询降级方案）。
     */
    @GetMapping("/tasks/{taskId}")
    public Result<QueryTaskVO> getTask(@PathVariable String taskId) {
        Long userId = UserContext.currentUserId();
        QueryTaskVO result = queryTaskService.getTaskResult(taskId, userId);
        return Result.success(result);
    }

    /**
     * 取消查询任务。
     * <p>
     * 同时更新 Java 侧状态并通知 Python Agent 停止执行。
     * </p>
     */
    @PostMapping("/tasks/{taskId}/cancel")
    public Result<Void> cancelTask(@PathVariable String taskId) {
        Long userId = UserContext.currentUserId();
        queryTaskService.cancelTask(taskId, userId);
        pythonAgentClient.cancelTask(taskId);
        return Result.success("任务已取消", null);
    }

    /**
     * 提交查询结果反馈（赞/踩）。
     * <p>
     * 更新审计日志中的 user_feedback 字段。
     * </p>
     */
    @PostMapping("/tasks/{taskId}/feedback")
    public Result<Void> submitFeedback(@PathVariable String taskId, @RequestBody Map<String, String> body) {
        String feedbackType = body.get("feedbackType");
        if (!"LIKE".equals(feedbackType) && !"DISLIKE".equals(feedbackType)) {
            throw new BusinessException("无效的反馈类型");
        }
        Long queryTaskDbId = queryTaskService.getTaskDbId(taskId, UserContext.currentUserId());
        auditLogService.updateFeedback(queryTaskDbId, feedbackType);
        return Result.success("反馈已提交", null);
    }

    /**
     * 查询会话消息列表。
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public Result<List<ConversationMessageVO>> listMessages(
            @PathVariable Long conversationId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "50") Integer pageSize) {
        Long userId = UserContext.currentUserId();
        List<ConversationMessageVO> messages = conversationService.listMessages(conversationId, userId, page, pageSize);
        return Result.success(messages);
    }

    /**
     * 查询用户的会话列表。
     */
    @GetMapping("/conversations")
    public Result<?> listConversations(
            @RequestParam(required = false) Long datasourceId) {
        Long userId = UserContext.currentUserId();
        return Result.success(conversationService.listConversations(userId, datasourceId));
    }

    /**
     * 删除（归档）会话。
     * <p>
     * 将指定会话标记为已归档，不再在会话列表中显示。
     * 只能删除自己的会话，无权限校验。
     * </p>
     *
     * @param conversationId 会话ID
     * @return 操作结果
     */
    @DeleteMapping("/conversations/{conversationId}")
    public Result<Void> deleteConversation(@PathVariable Long conversationId) {
        conversationService.archiveConversation(conversationId, UserContext.currentUserId());
        return Result.success("会话已删除", null);
    }

    /**
     * 查询历史任务列表。
     * <p>
     * 分页查询当前用户的查询历史，支持按状态、时间范围、数据源等条件过滤。
     * </p>
     *
     * @param query 查询条件（包含分页参数和过滤条件）
     * @return 历史任务列表
     */
    @GetMapping("/history")
    public Result<?> history(@ModelAttribute QueryHistoryQuery query) {
        return Result.success(queryTaskService.listHistory(UserContext.currentUserId(), query));
    }
}
