package com.dataocean.module.knowledge.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.knowledge.entity.dto.KnowledgePublishDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgePublicationVO.Detail;
import com.dataocean.module.knowledge.entity.vo.KnowledgePublicationVO.Outcome;
import com.dataocean.module.knowledge.entity.vo.KnowledgePublicationVO.Preview;
import com.dataocean.module.knowledge.entity.vo.KnowledgePublicationVO.Version;
import com.dataocean.module.knowledge.service.impl.KnowledgePublications;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 发布检查和切换与已提交结果回读使用同一权威来源。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/knowledge/sources/{source}")
public class KnowledgePublicationController {

    /**
     * 生效绑定及原命令核实服务。
     */
    private final KnowledgePublications publications;

    /**
     * 确认前核对本次范围、已通过的运行及实际索引。
     */
    @PostMapping("/releases/{id}/impact-preview")
    @RequirePermission("releases.publish")
    public Result<Preview> preview(
            @PathVariable String source,
            @PathVariable String id,
            @Valid @RequestBody KnowledgePublishDTO.Preview request) {
        return Result.success(publications.preview(Ids.parse(source), Ids.parse(id), request.getExpectedVersion()));
    }

    /**
     * 先提交正式指针，再独立确认实际结果。响应未知时仍可读取原命令。
     */
    @PostMapping("/releases/{id}/publish")
    @RequirePermission("releases.publish")
    public Result<MutationVO> publish(
            @PathVariable String source,
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgePublishDTO.Confirm request) {
        long origin = Ids.parse(source);
        long task = Ids.parse(id);
        var receipt = publications.publish(origin, task, command, request);
        publications.verifyCommitted(origin, task, Ids.parse(receipt.getTargetId()));
        return Result.success(receipt);
    }

    /**
     * 查询本次发布和当前实际版本，不重放原切换。
     */
    @GetMapping("/releases/{id}/publication")
    @RequirePermission("releases.view")
    public Result<Outcome> result(@PathVariable String source, @PathVariable String id) {
        return Result.success(publications.result(Ids.parse(source), Ids.parse(id)));
    }

    /**
     * 核对已提交组合，不调用第二次切换。
     */
    @PostMapping("/releases/{id}/reconciliations")
    @RequirePermission("releases.publish")
    public Result<MutationVO> reconcile(
            @PathVariable String source,
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgePublishDTO request) {
        return Result.success(
                publications.reconcile(Ids.parse(source), Ids.parse(id), command, request.getExpectedVersion()));
    }

    /**
     * 当前生效列表直接使用权威绑定，并保持同一来源分页。
     */
    @GetMapping("/bindings")
    @RequirePermission("releases.view")
    public Result<PageVO<Version>> list(
            @PathVariable String source,
            @RequestParam(required = false) String scopeId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String object,
            PageQuery page) {
        return Result.success(publications.list(Ids.parse(source), Ids.nullable(scopeId), keyword, object, page));
    }

    /**
     * 版本从选定绑定精确回读正文及直接上一版。
     */
    @GetMapping("/bindings/{id}")
    @RequirePermission("releases.view")
    public Result<Detail> detail(@PathVariable String source, @PathVariable String id) {
        return Result.success(publications.detail(Ids.parse(source), Ids.parse(id)));
    }
}
