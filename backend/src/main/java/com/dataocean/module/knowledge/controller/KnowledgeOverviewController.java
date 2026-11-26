package com.dataocean.module.knowledge.controller;

import com.dataocean.common.PageVO;
import com.dataocean.common.overview.OverviewQuery;
import com.dataocean.common.result.Result;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.knowledge.entity.vo.KnowledgeOverviewVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeOverviewVO.Detail;
import com.dataocean.module.knowledge.service.KnowledgeOverviewService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 知识概览的汇总和同口径明细。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/knowledge/overview")
public class KnowledgeOverviewController {

    /** 核实发生量、期末存量和下钻权限。 */
    private final KnowledgeOverviewService overview;

    /**
     * 查询授权范围内的知识统计。
     */
    @GetMapping
    @RequirePermission("knowledge-dashboard.view")
    public Result<KnowledgeOverviewVO> overview(@Valid @ModelAttribute OverviewQuery query) {
        return Result.success(overview.overview(query));
    }

    /**
     * 下钻时业务层另行核验原文档或发布任务查看权。
     */
    @GetMapping("/details")
    @RequirePermission("knowledge-dashboard.view")
    public Result<PageVO<Detail>> details(@Valid @ModelAttribute OverviewQuery query) {
        return Result.success(overview.details(query));
    }
}
