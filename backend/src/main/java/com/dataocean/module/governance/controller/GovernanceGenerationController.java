package com.dataocean.module.governance.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.result.Result;
import com.dataocean.module.governance.entity.dto.GenerateScopeDTO;
import com.dataocean.module.governance.entity.vo.GenerationAcceptedVO;
import com.dataocean.module.governance.entity.vo.GenerationDetailVO;
import com.dataocean.module.governance.entity.vo.GenerationPreviewVO;
import com.dataocean.module.governance.service.GovernanceGenerationService;
import com.dataocean.module.iam.security.RequirePermission;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 浏览器只通过授权的 Java 任务接口获取表说明建议。
 */
@RestController
@RequestMapping("/api/v1/governance/sources/{sourceId}")
@RequiredArgsConstructor
public class GovernanceGenerationController {

    /**
     * 负责业务侧委派、当前资格和实际结果发布。
     */
    private final GovernanceGenerationService tasks;

    /**
     * 预览与正式生成绑定同一明确范围。
     */
    @PostMapping("/objects/{objectId}/generation-preview")
    @RequirePermission("governance.suggest")
    public Result<GenerationPreviewVO> preview(
            @PathVariable String sourceId, @PathVariable String objectId, @Valid @RequestBody GenerateScopeDTO input) {
        return Result.success(tasks.preview(Ids.parse(sourceId), Ids.parse(objectId), input));
    }

    /**
     * 返回真实已接受状态，模型结果在后续任务回读中获取。
     */
    @PostMapping("/objects/{objectId}/generations")
    @RequirePermission("governance.suggest")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<GenerationAcceptedVO> accept(
            @PathVariable String sourceId, @PathVariable String objectId, @Valid @RequestBody GenerateScopeDTO input,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(tasks.accept(Ids.parse(sourceId), Ids.parse(objectId), input, command));
    }

    /**
     * 当前具有真实资格时查看任务状态和实际生成的结果。
     */
    @GetMapping("/generations/{requestId}")
    @RequirePermission("governance.view")
    public Result<GenerationDetailVO> detail(@PathVariable String sourceId, @PathVariable String requestId) {
        return Result.success(tasks.detail(Ids.parse(sourceId), requestId));
    }
}
