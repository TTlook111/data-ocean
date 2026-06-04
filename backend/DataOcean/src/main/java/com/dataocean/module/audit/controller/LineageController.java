package com.dataocean.module.audit.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.audit.entity.vo.ImpactAnalysisVO;
import com.dataocean.module.audit.entity.vo.LineageColumnVO;
import com.dataocean.module.audit.entity.vo.LineageTableVO;
import com.dataocean.module.audit.service.LineageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 血缘查询控制器
 * <p>
 * 提供表级和字段级血缘查询、变更影响分析 API。
 * 需要登录用户才能访问。
 * </p>
 */
@RestController
@RequestMapping("/api/lineage")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('audit:view')")
@Slf4j
public class LineageController {

    private final LineageService lineageService;

    /** 表级血缘查询 */
    @GetMapping("/table/{tableName}")
    public Result<List<LineageTableVO>> queryTableLineage(@PathVariable String tableName) {
        return Result.success(lineageService.queryTableLineage(tableName));
    }

    /** 字段级血缘查询 */
    @GetMapping("/column/{tableName}/{columnName}")
    public Result<List<LineageColumnVO>> queryColumnLineage(
            @PathVariable String tableName, @PathVariable String columnName) {
        return Result.success(lineageService.queryColumnLineage(tableName, columnName));
    }

    /** 变更影响分析 */
    @GetMapping("/impact/{tableName}/{columnName}")
    public Result<ImpactAnalysisVO> analyzeImpact(
            @PathVariable String tableName, @PathVariable String columnName) {
        return Result.success(lineageService.analyzeImpact(tableName, columnName));
    }
}
