package com.dataocean.module.knowledge.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.knowledge.entity.dto.KnowledgeRevisionConfirmDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeTestDraftDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeTestUpdateDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeTestVO;
import com.dataocean.module.knowledge.service.KnowledgeTests;

import java.util.List;

import jakarta.validation.Valid;

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
 * 来源内标准题的草稿、阅读、修订和审核。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/knowledge/sources/{source}/test-cases")
public class KnowledgeTestController {

    /**
     * 标准题当前办理入口。
     */
    private final KnowledgeTests tests;

    /**
     * 保留所属文档的实际查询范围。
     */
    @GetMapping
    @RequirePermission("test-cases.view")
    public Result<PageVO<KnowledgeTestVO.Row>> list(
            @PathVariable String source, @RequestParam(required = false) String documentId, PageQuery page) {
        return Result.success(tests.list(Ids.parse(source), Ids.nullable(documentId), page));
    }

    /**
     * 当前题单角色可读取已确认文档标识。
     */
    @GetMapping("/document-options")
    @RequirePermission("test-cases.view")
    public Result<PageVO<KnowledgeTestVO.DocumentOption>> documentOptions(@PathVariable String source, PageQuery page) {
        return Result.success(tests.documentOptions(Ids.parse(source), page));
    }

    /**
     * 实际文档确认约束数值题条件。
     */
    @GetMapping("/document-options/{document}/facts")
    @RequirePermission("test-cases.view")
    public Result<List<KnowledgeTestVO.FactOption>> factOptions(
            @PathVariable String source, @PathVariable String document, @RequestParam String revisionId) {
        return Result.success(tests.factOptions(Ids.parse(source), Ids.parse(document), Ids.parse(revisionId)));
    }

    /**
     * 阅读实际题干、预期和固定依据。
     */
    @GetMapping("/{id}")
    @RequirePermission("test-cases.view")
    public Result<KnowledgeTestVO> detail(@PathVariable String source, @PathVariable String id) {
        return Result.success(tests.detail(Ids.parse(source), Ids.parse(id)));
    }

    /**
     * 新增可部分填写的题单草稿。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("test-cases.edit")
    public Result<MutationVO> create(
            @PathVariable String source,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgeTestDraftDTO input) {
        return Result.success(tests.create(Ids.parse(source), command, input));
    }

    /**
     * 保存新修订，保留历史审核。
     */
    @PutMapping("/{id}/draft")
    @RequirePermission("test-cases.edit")
    public Result<MutationVO> draft(
            @PathVariable String source,
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgeTestUpdateDTO input) {
        return Result.success(tests.draft(Ids.parse(source), Ids.parse(id), command, input));
    }

    /**
     * 审核实际阅读的确切正文与条件。
     */
    @PostMapping("/{id}/reviews")
    @RequirePermission("test-cases.review")
    public Result<MutationVO> review(
            @PathVariable String source,
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgeRevisionConfirmDTO input) {
        return Result.success(tests.review(Ids.parse(source), Ids.parse(id), command, input));
    }
}
