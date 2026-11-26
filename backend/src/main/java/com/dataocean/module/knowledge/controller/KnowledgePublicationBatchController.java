package com.dataocean.module.knowledge.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.result.Result;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.knowledge.entity.dto.KnowledgeBatchPublishDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeBatchRollbackDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeBatchSelectionDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgePublicationBatchVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgePublicationBatchVO.Preview;
import com.dataocean.module.knowledge.service.impl.KnowledgePublicationBatches;
import com.dataocean.module.knowledge.service.impl.KnowledgeRollbacks;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 同一发布范围的整批切换、回退与结果确认。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/knowledge/sources/{source}")
public class KnowledgePublicationBatchController {

    /**
     * 正式固定发布范围及操作历史。
     */
    private final KnowledgePublicationBatches publications;

    /**
     * 真实目标核对及回退动作。
     */
    private final KnowledgeRollbacks rollbacks;

    /**
     * 对已逐题通过的独立准备任务核对一个完整发布批次。
     */
    @PostMapping("/publication-batches/impact-preview")
    @RequirePermission("releases.publish")
    public Result<Preview> publicationPreview(
            @PathVariable String source, @Valid @RequestBody KnowledgeBatchSelectionDTO request) {
        return Result.success(publications.preview(Ids.parse(source), request.getMembers()));
    }

    /**
     * 先原子提交实际切换，再尝试核实完整结果。
     */
    @PostMapping("/publication-batches")
    @RequirePermission("releases.publish")
    public Result<MutationVO> publish(
            @PathVariable String source,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgeBatchPublishDTO request) {
        long origin = Ids.parse(source);
        var receipt = publications.publish(origin, command, request);
        publications.verifyCommitted(origin, Ids.parse(receipt.getTargetId()));
        return Result.success(receipt);
    }

    /**
     * 回看原发布批次、当前生效范围和已提交回退。
     */
    @GetMapping("/publication-batches/{id}")
    @RequirePermission("releases.view")
    public Result<KnowledgePublicationBatchVO.Result> result(@PathVariable String source, @PathVariable String id) {
        return Result.success(publications.result(Ids.parse(source), Ids.parse(id)));
    }

    /**
     * 正式版本从确切的绑定定位原批次。
     */
    @GetMapping("/bindings/{id}/publication-batch")
    @RequirePermission("releases.view")
    public Result<KnowledgePublicationBatchVO.Result> forBinding(@PathVariable String source, @PathVariable String id) {
        return Result.success(publications.forBinding(Ids.parse(source), Ids.parse(id)));
    }

    /**
     * 问题版本和恢复生效的版本均可以回看原操作。
     */
    @GetMapping("/bindings/{id}/rollback-history")
    @RequirePermission("releases.view")
    public Result<KnowledgePublicationBatchVO.Result> rollbackHistory(
            @PathVariable String source, @PathVariable String id) {
        return Result.success(publications.rollbackForBinding(Ids.parse(source), Ids.parse(id)));
    }

    /**
     * 核实已提交的完整发布组合。
     */
    @PostMapping("/publication-batches/{id}/reconciliations")
    @RequirePermission("releases.publish")
    public Result<MutationVO> reconcile(
            @PathVariable String source, @PathVariable String id, @RequestHeader("Idempotency-Key") String command) {
        return Result.success(publications.reconcile(Ids.parse(source), Ids.parse(id), command));
    }

    /**
     * 回看实际生效及全部直接上一版目标和阻断原因。
     */
    @GetMapping("/publication-batches/{id}/rollback-preview")
    @RequirePermission("versions.rollback")
    public Result<Preview> rollbackPreview(@PathVariable String source, @PathVariable String id) {
        return Result.success(rollbacks.preview(Ids.parse(source), Ids.parse(id)));
    }

    /**
     * 确认固定范围的原子回退并核实实际结果。
     */
    @PostMapping("/publication-batches/{id}/rollback")
    @RequirePermission("versions.rollback")
    public Result<MutationVO> rollback(
            @PathVariable String source,
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgeBatchRollbackDTO request) {
        long origin = Ids.parse(source);
        long batch = Ids.parse(id);
        var receipt = rollbacks.rollback(origin, batch, command, request);
        rollbacks.verifyCommitted(origin, batch, Ids.parse(receipt.getTargetId()));
        return Result.success(receipt);
    }

    /**
     * 核实原回退完整组合。
     */
    @PostMapping("/publication-batches/{id}/rollback-reconciliations")
    @RequirePermission("versions.rollback")
    public Result<MutationVO> reconcileRollback(
            @PathVariable String source, @PathVariable String id, @RequestHeader("Idempotency-Key") String command) {
        return Result.success(rollbacks.reconcile(Ids.parse(source), Ids.parse(id), command));
    }
}
