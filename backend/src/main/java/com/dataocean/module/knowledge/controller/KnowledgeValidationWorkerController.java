package com.dataocean.module.knowledge.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.knowledge.service.KnowledgeValidationWire.Input;
import com.dataocean.module.knowledge.service.impl.KnowledgeValidations;

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
 * 验证工作者仅使用同一受认证服务身份领取和复核。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/knowledge/validations")
@ConditionalOnProperty(name = "dataocean.governance-ai.enabled", havingValue = "true")
public class KnowledgeValidationWorkerController {

    /**
     * 耐久执行记录和当次来源资格。
     */
    private final KnowledgeValidations validations;

    /**
     * 一次性领取确切索引代次和模型调用许可。
     */
    @PostMapping("/{id}/input")
    @RequirePermission(access = RequirePermission.Access.SERVICE)
    public ResponseEntity<Result<Input>> input(@PathVariable UUID id, @RequestParam int runVersion) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Result.success(validations.claimInput(id.toString(), runVersion)));
    }

    /**
     * 在各批处理前后核验实际操作资格。
     */
    @PostMapping("/{id}/recheck")
    @RequirePermission(access = RequirePermission.Access.SERVICE)
    public Result<Boolean> recheck(@PathVariable UUID id, @RequestParam int runVersion) {
        validations.recheck(id.toString(), runVersion);
        return Result.success(true);
    }
}
