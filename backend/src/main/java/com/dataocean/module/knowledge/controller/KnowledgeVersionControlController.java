package com.dataocean.module.knowledge.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.result.Result;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.knowledge.entity.dto.KnowledgeVersionPauseDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeVersionRestoreDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeWithdrawalDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeVersionControlVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeVersionControlVO.RunDetail;
import com.dataocean.module.knowledge.entity.vo.KnowledgeWithdrawalVO.Detail;
import com.dataocean.module.knowledge.entity.vo.KnowledgeWithdrawalVO.Preview;
import com.dataocean.module.knowledge.service.KnowledgeRecoveryService;
import com.dataocean.module.knowledge.service.KnowledgeVersionService;
import com.dataocean.module.knowledge.service.KnowledgeWithdrawalService;

import java.util.UUID;

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
 * 生效版本的使用控制和受限验证入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/knowledge/sources/{source}/bindings/{id}")
public class KnowledgeVersionControlController {

    /**
     * 当前限制和问数使用规则。
     */
    private final KnowledgeVersionService versionUse;

    /**
     * 共享实际验证及裁定结果。
     */
    private final KnowledgeRecoveryService recovery;

    /**
     * 知识撤下的影响核对及持久办理。
     */
    private final KnowledgeWithdrawalService withdrawals;

    /**
     * 展示确切目标、直接和间接引用方。
     */
    @GetMapping("/withdrawal-preview")
    @RequirePermission("releases.withdraw")
    public Result<Preview> withdrawalPreview(@PathVariable String source, @PathVariable String id) {
        return Result.success(withdrawals.preview(Ids.parse(source), Ids.parse(id)));
    }

    /**
     * 只允许按用户看到的实际范围整批撤下。
     */
    @PostMapping("/withdrawals")
    @RequirePermission("releases.withdraw")
    public Result<MutationVO> withdraw(
            @PathVariable String source,
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgeWithdrawalDTO input) {
        return Result.success(withdrawals.withdraw(Ids.parse(source), Ids.parse(id), command, input));
    }

    /**
     * 不改写旧正文，单独回看本批撤下原因和记录。
     */
    @GetMapping("/withdrawals")
    @RequirePermission("releases.view")
    public Result<Detail> withdrawalHistory(@PathVariable String source, @PathVariable String id) {
        return Result.success(withdrawals.history(Ids.parse(source), Ids.parse(id)));
    }

    /**
     * 可回看限制、原原因及本次恢复的办理由来。
     */
    @GetMapping("/controls")
    @RequirePermission("releases.view")
    public Result<KnowledgeVersionControlVO> detail(@PathVariable String source, @PathVariable String id) {
        return Result.success(recovery.controls(Ids.parse(source), Ids.parse(id)));
    }

    /**
     * 暂停保留本次正式绑定及历史。
     */
    @PostMapping("/pauses")
    @RequirePermission("versions.pause")
    public Result<MutationVO> pause(
            @PathVariable String source,
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgeVersionPauseDTO input) {
        return Result.success(versionUse.pause(Ids.parse(source), Ids.parse(id), command, input));
    }

    /**
     * 按同一暂停范围运行隔离验证，不取得普通问数使用权。
     */
    @PostMapping("/pauses/{pause}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequirePermission("releases.test")
    public Result<MutationVO> start(
            @PathVariable String source,
            @PathVariable String id,
            @PathVariable String pause,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(recovery.startRecovery(Ids.parse(source), Ids.parse(id), Ids.parse(pause), command));
    }

    /**
     * 回看指定正式绑定的一轮隔离验证。
     */
    @GetMapping("/recovery/{run}")
    @RequirePermission("releases.view")
    public Result<RunDetail> run(@PathVariable String source, @PathVariable String id, @PathVariable UUID run) {
        return Result.success(recovery.recoveryDetail(Ids.parse(source), Ids.parse(id), run.toString()));
    }

    /**
     * 核对本次通过依据并明确解除暂停。
     */
    @PostMapping("/pauses/{pause}/restore")
    @RequirePermission("governance.allow")
    public Result<MutationVO> restore(
            @PathVariable String source,
            @PathVariable String id,
            @PathVariable String pause,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgeVersionRestoreDTO input) {
        return Result.success(recovery.restore(Ids.parse(source), Ids.parse(id), Ids.parse(pause), command, input));
    }

    /**
     * 问数侧读取使用资格，成功只表明该绑定当前允许参与，不披露正文与索引。
     */
    @GetMapping("/query-use")
    @RequirePermission("query.ask")
    public Result<String> queryUse(@PathVariable String source, @PathVariable String id) {
        return Result.success(Ids.text(
                versionUse.requireForQuery(Ids.parse(source), Ids.parse(id)).id()));
    }
}
