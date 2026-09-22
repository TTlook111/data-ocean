package com.dataocean.module.query.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.query.entity.dto.IamS1QueryAskRequestDTO;
import com.dataocean.module.query.entity.query.QueryHistoryQuery;
import com.dataocean.module.query.entity.vo.QueryTaskVO;
import com.dataocean.module.query.service.IamS1QueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * IAM-SIMPLE-1 正式用户查询入口。旧 /api/query 仅保留给 B6-3 清理。
 */
@RestController
@RequestMapping("/api/iam-s1/query")
@RequiredArgsConstructor
public class IamS1QueryController {
    private final IamS1QueryService queryService;

    @PostMapping("/ask")
    public Result<Map<String, Object>> ask(@Valid @RequestBody IamS1QueryAskRequestDTO request) {
        String taskId = queryService.submit(UserContext.currentUserId(), request);
        Long conversationId = queryService.conversationId(taskId, UserContext.currentUserId());
        Map<String, Object> response = new HashMap<>();
        response.put("taskId", taskId);
        response.put("protocolVersion", "IAM-SIMPLE-1");
        if (conversationId != null) response.put("conversationId", conversationId);
        return Result.success("IAM-SIMPLE-1 查询已提交", response);
    }

    @GetMapping("/tasks/{taskId}")
    public Result<QueryTaskVO> get(@PathVariable String taskId) {
        return Result.success(queryService.get(taskId, UserContext.currentUserId()));
    }

    @GetMapping("/tasks/{taskId}/sql")
    public Result<Map<String, Object>> viewSql(@PathVariable String taskId) {
        QueryTaskVO task = queryService.get(taskId, UserContext.currentUserId());
        if (task.getSql() == null || !Boolean.TRUE.equals(task.getCanViewSql())) {
            throw new com.dataocean.common.exception.BusinessException("没有当前 S1 SQL 查看能力");
        }
        return Result.success(Map.of("protocolVersion", "IAM-SIMPLE-1", "taskId", taskId,
                "permissionRevision", task.getPermissionRevision(), "sql", task.getSql()));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Result<Void> cancel(@PathVariable String taskId) {
        queryService.cancel(taskId, UserContext.currentUserId());
        return Result.success("S1 任务已取消", null);
    }

    @GetMapping("/history")
    public Result<?> history(@ModelAttribute QueryHistoryQuery query) {
        return Result.success(queryService.history(UserContext.currentUserId(), query));
    }

    @GetMapping("/conversations")
    public Result<?> conversations(@RequestParam(required = false) Long datasourceId) {
        return Result.success(queryService.conversations(UserContext.currentUserId(), datasourceId));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public Result<?> conversationMessages(@PathVariable Long conversationId,
                                          @RequestParam(defaultValue = "1") Integer page,
                                          @RequestParam(defaultValue = "50") Integer pageSize) {
        return Result.success(queryService.conversationMessages(
                conversationId, UserContext.currentUserId(), page, pageSize));
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Result<Void> archiveConversation(@PathVariable Long conversationId) {
        queryService.archiveConversation(conversationId, UserContext.currentUserId());
        return Result.success("会话已删除", null);
    }

    @GetMapping("/tasks/{taskId}/export")
    public Result<List<Map<String, Object>>> export(@PathVariable String taskId) {
        return Result.success(queryService.export(taskId, UserContext.currentUserId()));
    }

    @GetMapping(value = "/tasks/{taskId}/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv(@PathVariable String taskId) {
        List<Map<String, Object>> rows = queryService.export(taskId, UserContext.currentUserId());
        StringBuilder csv = new StringBuilder();
        if (!rows.isEmpty()) {
            List<String> headers = new java.util.ArrayList<>(rows.get(0).keySet());
            csv.append(headers.stream().map(this::csvCell).collect(java.util.stream.Collectors.joining(","))).append('\n');
            for (Map<String, Object> row : rows) {
                csv.append(headers.stream().map(header -> csvCell(row.get(header))).collect(java.util.stream.Collectors.joining(","))).append('\n');
            }
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String csvCell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    @PostMapping("/tasks/{taskId}/feedback")
    public Result<Void> feedback(@PathVariable String taskId, @RequestBody Map<String, String> body) {
        queryService.feedback(taskId, UserContext.currentUserId(), body == null ? null : body.get("feedbackType"));
        return Result.success("反馈已提交", null);
    }
}
