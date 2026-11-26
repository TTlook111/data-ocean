package com.dataocean.module.knowledge.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.knowledge.service.KnowledgeBuildService;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Input;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 构建工作者使用既有机器身份领取已保存的任务。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/knowledge/builds")
@ConditionalOnProperty(name = "dataocean.governance-ai.enabled", havingValue = "true")
public class KnowledgeIndexWorkerController {

    /**
     * 确切构建上下文及当次执行资格。
     */
    private final KnowledgeBuildService builds;

    /**
     * 当次工作者取得一次短期模型调用输入，所有响应禁止缓存。
     */
    @PostMapping("/{id}/input")
    @RequirePermission(access = RequirePermission.Access.SERVICE)
    public ResponseEntity<Result<Input>> input(@PathVariable UUID id, @RequestParam int runVersion) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Result.success(builds.claimInput(id.toString(), runVersion)));
    }

    /**
     * 下一批外发、索引写入及成果回读前复核原任务。
     */
    @PostMapping("/{id}/recheck")
    @RequirePermission(access = RequirePermission.Access.SERVICE)
    public Result<Boolean> recheck(@PathVariable UUID id, @RequestParam int runVersion) {
        builds.recheck(id.toString(), runVersion);
        return Result.success(true);
    }
}
