package com.dataocean.module.governance.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.governance.service.GovernanceGenerationWire.Context;
import com.dataocean.module.governance.service.impl.GovernanceGenerationTasks;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.model.service.ModelInvocation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 独立工作者读取当次仍有权取用的受控上下文。
 */
@RestController
@RequestMapping("/internal/v1/governance/tasks")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dataocean.governance-ai.enabled", havingValue = "true")
public class GovernanceWorkerController {

    /**
     * 权威任务绑定与当前用户授权。
     */
    private final GovernanceGenerationTasks tasks;

    /**
     * 工作者执行前使用真实任务与执行代次核对来源。
     */
    @GetMapping("/{requestId}/context")
    @RequirePermission(access = RequirePermission.Access.SERVICE)
    public Result<Context> context(@PathVariable String requestId, @RequestParam int runVersion) {
        return Result.success(tasks.context(requestId, runVersion));
    }

    /**
     * 调用前按当次任务和代次领取一次必要凭据，内部响应禁止缓存。
     */
    @PostMapping("/{requestId}/model")
    @RequirePermission(access = RequirePermission.Access.SERVICE)
    public ResponseEntity<Result<ModelInvocation>> model(@PathVariable String requestId, @RequestParam int runVersion) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.success(tasks.authorizeModel(requestId, runVersion)));
    }

    /**
     * 每次外发前复核字段 Agent 的当前任务资格。
     */
    @PostMapping("/{requestId}/model/recheck")
    @RequirePermission(access = RequirePermission.Access.SERVICE)
    public Result<Boolean> recheck(@PathVariable String requestId, @RequestParam int runVersion) {
        tasks.verifyContinuation(requestId, runVersion);
        return Result.success(true);
    }
}
