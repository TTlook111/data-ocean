package com.dataocean.module.dashboard.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.dashboard.entity.query.QueryExperienceDetailsQuery;
import com.dataocean.module.dashboard.entity.vo.QueryExperienceDetailVO;
import com.dataocean.module.dashboard.entity.vo.QueryExperienceVO;
import com.dataocean.module.dashboard.service.QueryExperienceService;
import com.dataocean.module.permission.s1.annotation.IamS1Global;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 工作台入口权限只允许打开总览，各域的真实数据由服务按负责源另行授权。 */
@RestController
@RequestMapping("/api/admin/dashboard/query-experience")
@RequiredArgsConstructor
public class QueryExperienceController {
    private final QueryExperienceService service;

    @GetMapping
    @IamS1Global("admin:workbench:view")
    public Result<QueryExperienceVO> summary(@RequestParam(defaultValue = "today") String period) {
        return Result.success(service.summary(UserContext.currentUserId(), period));
    }

    /** 明细每次重新判权，切换账号或撤销负责范围后无法沿用旧页的可见性。 */
    @GetMapping("/details")
    @IamS1Global("admin:workbench:view")
    public Result<Page<QueryExperienceDetailVO>> details(QueryExperienceDetailsQuery query) {
        return Result.success(service.details(UserContext.currentUserId(), query));
    }
}
