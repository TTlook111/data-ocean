package com.dataocean.module.governance.controller;

import com.dataocean.common.PageVO;
import com.dataocean.common.overview.OverviewQuery;
import com.dataocean.common.result.Result;
import com.dataocean.module.governance.entity.vo.GovernanceOverviewVO;
import com.dataocean.module.governance.entity.vo.GovernanceOverviewVO.Detail;
import com.dataocean.module.governance.service.GovernanceOverviewService;
import com.dataocean.module.iam.security.RequirePermission;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 治理概览的汇总和对账接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/governance/overview")
public class GovernanceOverviewController {

    /**
     * 按期间和权限核对治理台账。
     */
    private final GovernanceOverviewService overview;

    /**
     * 提供当前有效角色允许的治理汇总。
     */
    @GetMapping
    @RequirePermission("governance-dashboard.view")
    public Result<GovernanceOverviewVO> overview(@Valid @ModelAttribute OverviewQuery query) {
        return Result.success(overview.overview(query));
    }

    /**
     * 原记录仍须具备治理工作台查看权限。
     */
    @GetMapping("/details")
    @RequirePermission({"governance-dashboard.view", "governance.view"})
    public Result<PageVO<Detail>> details(@Valid @ModelAttribute OverviewQuery query) {
        return Result.success(overview.details(query));
    }
}
