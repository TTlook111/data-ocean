package com.dataocean.module.knowledge.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.knowledge.entity.vo.KnowledgeReleasePreflightVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeTestVO;
import com.dataocean.module.knowledge.service.KnowledgeReleasePreflight;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 按实际来源核对发布准备，查询不会改变版本指针。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/knowledge/sources/{source}/release-preflight")
public class KnowledgeReleasePreflightController {

    /**
     * 核对当前确认修订和构建前置。
     */
    private final KnowledgeReleasePreflight preflight;

    /**
     * 仅提供当前源的已确认文档标识。
     */
    @GetMapping("/document-options")
    @RequirePermission("releases.view")
    public Result<PageVO<KnowledgeTestVO.DocumentOption>> options(@PathVariable String source, PageQuery page) {
        return Result.success(preflight.options(Ids.parse(source), page));
    }

    /**
     * 读取实际文档与题单就绪情况。
     */
    @GetMapping
    @RequirePermission("releases.view")
    public Result<KnowledgeReleasePreflightVO> inspect(@PathVariable String source, @RequestParam String documentId) {
        return Result.success(preflight.inspect(Ids.parse(source), Ids.parse(documentId)));
    }
}
