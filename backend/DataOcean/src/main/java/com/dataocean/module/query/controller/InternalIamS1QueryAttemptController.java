package com.dataocean.module.query.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.query.entity.dto.IamS1AttemptAuthorizeRequestDTO;
import com.dataocean.module.query.entity.dto.IamS1AttemptResultRequestDTO;
import com.dataocean.module.query.entity.dto.IamS1ModelCallBudgetDTO;
import com.dataocean.module.query.service.IamS1QueryAttemptService;
import com.dataocean.module.query.service.IamS1QueryBudgetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Python -> Java per-attempt S1 gate; /internal/** authentication is provided by InternalTokenFilter. */
@RestController
@RequestMapping("/internal/iam-s1/query/tasks/{taskId}/attempts")
@RequiredArgsConstructor
public class InternalIamS1QueryAttemptController {
    private final IamS1QueryAttemptService attemptService;
    private final IamS1QueryBudgetService budgetService;

    @PostMapping("/authorize")
    public Result<Map<String, Object>> authorize(@PathVariable String taskId,
                                                 @Valid @RequestBody IamS1AttemptAuthorizeRequestDTO request) {
        return Result.success(attemptService.authorize(taskId, request));
    }

    @PostMapping("/{attemptId}/executing")
    public Result<Map<String, Object>> markExecuting(@PathVariable String taskId,
                                                     @PathVariable String attemptId,
                                                     @RequestBody Map<String, String> body) {
        return Result.success(attemptService.markExecuting(taskId, attemptId, body == null ? null : body.get("sqlHash")));
    }

    @PostMapping("/{attemptId}/protect")
    public Result<Map<String, Object>> protectResult(@PathVariable String taskId,
                                                     @PathVariable String attemptId,
                                                     @Valid @RequestBody IamS1AttemptResultRequestDTO request) {
        if (!attemptId.equals(request.getAttemptId())) {
            throw new com.dataocean.common.exception.BusinessException("执行尝试身份不一致");
        }
        return Result.success(attemptService.protectResult(taskId, request));
    }

    @PostMapping("/budget/reserve")
    public Result<Map<String, Object>> reserveBudget(@PathVariable String taskId,
                                                     @Valid @RequestBody IamS1ModelCallBudgetDTO request) {
        return Result.success(budgetService.reserve(taskId, request));
    }

    @PostMapping("/budget/settle")
    public Result<Map<String, Object>> settleBudget(@PathVariable String taskId,
                                                    @Valid @RequestBody IamS1ModelCallBudgetDTO request) {
        return Result.success(budgetService.settle(taskId, request));
    }
}
