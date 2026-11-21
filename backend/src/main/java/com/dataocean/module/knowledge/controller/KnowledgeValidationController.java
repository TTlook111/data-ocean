package com.dataocean.module.knowledge.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.result.Result;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.knowledge.entity.dto.KnowledgeValidationDTO.Judgment;
import com.dataocean.module.knowledge.entity.dto.KnowledgeValidationDTO.Start;
import com.dataocean.module.knowledge.entity.vo.KnowledgeValidationVO;
import com.dataocean.module.knowledge.service.impl.KnowledgeValidations;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 发布任务中的逐题验证、历史证据和人工判断入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/knowledge/sources/{source}/releases/{id}/validations")
public class KnowledgeValidationController {

    /**
     * 同一候选运行及实际技术结果的业务边界。
     */
    private final KnowledgeValidations validations;

    /**
     * 回读当前运行或指定旧运行，由服务按读者权限裁剪。
     */
    @GetMapping
    @RequirePermission("releases.view")
    public Result<KnowledgeValidationVO> detail(
            @PathVariable String source, @PathVariable String id, @RequestParam(required = false) UUID runId) {
        return Result.success(
                validations.detail(Ids.parse(source), Ids.parse(id), runId == null ? null : runId.toString()));
    }

    /**
     * 已就绪索引的手动运行及重测。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequirePermission("releases.test")
    public Result<MutationVO> start(
            @PathVariable String source,
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody Start input) {
        return Result.success(validations.start(Ids.parse(source), Ids.parse(id), command, input));
    }

    /**
     * 当前运行中的待裁定说明题只能提交一次人工结论。
     */
    @PostMapping("/{run}/results/{question}/judgment")
    @RequirePermission("test-cases.judge")
    public Result<MutationVO> judge(
            @PathVariable String source,
            @PathVariable String id,
            @PathVariable UUID run,
            @PathVariable String question,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody Judgment input) {
        return Result.success(validations.judge(
                Ids.parse(source), Ids.parse(id), run.toString(), Ids.parse(question), command, input));
    }
}
