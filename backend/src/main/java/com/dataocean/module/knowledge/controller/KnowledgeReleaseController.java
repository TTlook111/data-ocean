package com.dataocean.module.knowledge.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.knowledge.entity.dto.KnowledgeReleasePrepareDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeReleaseCandidateVO;
import com.dataocean.module.knowledge.service.KnowledgeReleaseService;

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
 * 发布候选准备及来源范围内的读取入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/knowledge/sources/{source}/releases")
public class KnowledgeReleaseController {

    /**
     * 候选固定与回读业务。
     */
    private final KnowledgeReleaseService releases;

    /**
     * 按当前源和选择的文档分页返回候选。
     */
    @GetMapping
    @RequirePermission("releases.view")
    public Result<PageVO<KnowledgeReleaseCandidateVO>> list(
            @PathVariable String source, @RequestParam String documentId, PageQuery page) {
        return Result.success(releases.list(Ids.parse(source), Ids.parse(documentId), page));
    }

    /**
     * 从已核对的确认修订创建可供后续构建使用的候选。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("releases.build")
    public Result<MutationVO> prepare(
            @PathVariable String source,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgeReleasePrepareDTO input) {
        return Result.success(releases.prepare(Ids.parse(source), command, input));
    }
}
