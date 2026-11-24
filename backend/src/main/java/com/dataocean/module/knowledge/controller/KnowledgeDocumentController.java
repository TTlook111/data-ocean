package com.dataocean.module.knowledge.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.governance.entity.vo.GovernanceFactOptionVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.knowledge.entity.dto.KnowledgeDocumentDraftDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeDocumentUpdateDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgePlanDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeRevisionConfirmDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeDocumentVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgePlanVO;
import com.dataocean.module.knowledge.service.KnowledgeDocuments;
import com.dataocean.module.knowledge.service.KnowledgePlans;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

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

import lombok.RequiredArgsConstructor;

/**
 * 当前源的知识依据、草稿及人工确认。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/knowledge/sources/{source}")
public class KnowledgeDocumentController {

    /**
     * 知识文档使用的持久化业务入口。
     */
    private final KnowledgeDocuments documents;

    /**
     * 当前源独立规划任务。
     */
    private final KnowledgePlans plans;

    /**
     * 按已核验的治理输入发起实际文档规划。
     */
    @PostMapping("/plans")
    @RequirePermission("knowledge.generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<MutationVO> plan(
            @PathVariable String source,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgePlanDTO input) {
        return Result.success(plans.create(Ids.parse(source), command, input));
    }

    /**
     * 查询原规划状态及其同源建议。
     */
    @GetMapping("/plans/{id}")
    @RequirePermission("knowledge.generate")
    public Result<KnowledgePlanVO> planDetail(@PathVariable String source, @PathVariable String id) {
        return Result.success(plans.detail(Ids.parse(source), Ids.parse(id)));
    }

    /**
     * 按当前源读取可选择的实际治理确认。
     */
    @GetMapping("/facts")
    @RequirePermission("knowledge.view")
    public Result<PageVO<GovernanceFactOptionVO>> facts(
            @PathVariable String source,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String objectId,
            PageQuery page) {
        return Result.success(documents.facts(Ids.parse(source), kind, Ids.nullable(objectId), page));
    }

    /**
     * 查看当前源的知识文档列表。
     */
    @GetMapping("/documents")
    @RequirePermission("knowledge.view")
    public Result<PageVO<KnowledgeDocumentVO.Row>> list(
            @PathVariable String source,
            @RequestParam(required = false) @Size(max = 120) String keyword,
            PageQuery page) {
        return Result.success(documents.list(Ids.parse(source), keyword, page));
    }

    /**
     * 查阅当前正文或明确指定的历史正文。
     */
    @GetMapping("/documents/{id}")
    @RequirePermission("knowledge.view")
    public Result<KnowledgeDocumentVO> detail(
            @PathVariable String source, @PathVariable String id, @RequestParam(required = false) String revisionId) {
        return Result.success(documents.detail(Ids.parse(source), Ids.parse(id), Ids.nullable(revisionId)));
    }

    /**
     * 固定用户采纳的事实并保存首份草稿。
     */
    @PostMapping("/documents")
    @RequirePermission("knowledge.edit")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<MutationVO> create(
            @PathVariable String source,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgeDocumentDraftDTO input) {
        return Result.success(documents.create(Ids.parse(source), command, input));
    }

    /**
     * 保存本次行内编辑和对应事实修订。
     */
    @PutMapping("/documents/{id}/draft")
    @RequirePermission("knowledge.edit")
    public Result<MutationVO> draft(
            @PathVariable String source,
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgeDocumentUpdateDTO input) {
        return Result.success(documents.draft(Ids.parse(source), Ids.parse(id), command, input));
    }

    /**
     * 按当前修订核对实际内容及依赖后确认。
     */
    @PostMapping("/documents/{id}/confirmations")
    @RequirePermission("knowledge.confirm")
    public Result<MutationVO> confirm(
            @PathVariable String source,
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgeRevisionConfirmDTO input) {
        return Result.success(documents.confirm(Ids.parse(source), Ids.parse(id), command, input));
    }
}
