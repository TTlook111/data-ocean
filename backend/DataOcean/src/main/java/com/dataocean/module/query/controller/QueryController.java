package com.dataocean.module.query.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.datasource.service.DatasourceAccessService;
import com.dataocean.module.query.client.PythonAgentClient;
import com.dataocean.module.query.entity.dto.QueryAskDTO;
import com.dataocean.module.query.entity.vo.ConversationMessageVO;
import com.dataocean.module.query.entity.vo.QueryTaskVO;
import com.dataocean.module.query.service.ConversationService;
import com.dataocean.module.query.service.QueryTaskService;
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

        // 校验用户是否有权访问该数据源
        datasourceAccessService.checkAccess(request.getDatasourceId());

        // 获取或创建会话
        Long conversationId = conversationService.getOrCreateConversation(
                userId, request.getDatasourceId(), request.getConversationId(), request.getQuestion());

        // 保存用户消息
        conversationService.saveUserMessage(conversationId, request.getQuestion());

        // 提交查询任务
        String taskId = queryTaskService.submitQuery(userId, request.getDatasourceId(), request.getQuestion(), conversationId);

        // 异步触发 Python Agent 执行
        pythonAgentClient.executeAsync(taskId, request.getDatasourceId(), userId,
                request.getQuestion(), conversationId, 0L);

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
     */
    @PostMapping("/tasks/{taskId}/cancel")
    public Result<Void> cancelTask(@PathVariable String taskId) {
        Long userId = UserContext.currentUserId();
        queryTaskService.cancelTask(taskId, userId);
        return Result.success("任务已取消", null);
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
}
