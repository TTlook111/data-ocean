package com.dataocean.module.governance.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.governance.entity.dto.GovernanceCandidateReviewDTO;
import com.dataocean.module.governance.entity.vo.GenerationAcceptedVO;
import com.dataocean.module.governance.entity.vo.GovernanceCandidateVO;
import com.dataocean.module.governance.service.impl.GovernanceDiscovery;
import com.dataocean.module.governance.service.impl.GovernanceGenerationTasks;
import com.dataocean.module.iam.security.RequirePermission;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 表治理详情内进行发现、依据回看和人工审核。
 */
@RestController
@RequestMapping("/api/v1/governance/sources/{source}/objects/{object}/candidates")
@RequiredArgsConstructor
public class GovernanceDiscoveryController {

    /** 当前候选与人工修订。 */
    private final GovernanceDiscovery discovery;

    /** 正式治理接收及队列调度。 */
    private final GovernanceGenerationTasks tasks;

    /**
     * 发现任务使用正式权限和稳定命令。
     */
    @PostMapping("/discovery/{kind}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequirePermission("governance.suggest")
    public Result<GenerationAcceptedVO> discover(@PathVariable String source, @PathVariable String object,
            @PathVariable String kind, @RequestHeader("Idempotency-Key") String command) {
        return Result.success(tasks.discover(Ids.parse(source), Ids.parse(object), kind, command));
    }

    /**
     * 只列当前同源表有关的候选。
     */
    @GetMapping
    @RequirePermission("governance.view")
    public Result<PageVO<GovernanceCandidateVO>> list(@PathVariable String source, @PathVariable String object,
            @RequestParam String kind, PageQuery query) {
        return Result.success(discovery.list(Ids.parse(source), Ids.parse(object), kind, query));
    }

    /**
     * 服务层分开检查保存与审核权限，并按版本办理。
     */
    @PutMapping("/{candidate}")
    @RequirePermission("governance.view")
    public Result<GovernanceCandidateVO> review(@PathVariable String source, @PathVariable String object,
            @PathVariable String candidate, @Valid @RequestBody GovernanceCandidateReviewDTO data) {
        return Result.success(discovery.review(Ids.parse(source), Ids.parse(object), Ids.parse(candidate), data));
    }
}
