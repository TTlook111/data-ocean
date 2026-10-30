package com.dataocean.module.audit.controller;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.result.Result;
import com.dataocean.module.permission.s1.annotation.IamS1Resource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.audit.entity.vo.ImpactAnalysisVO;
import com.dataocean.module.audit.entity.vo.LineageColumnVO;
import com.dataocean.module.audit.entity.vo.LineageTableVO;
import com.dataocean.module.audit.service.LineageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class LineageController {

    /** 查看数据血缘：负责源范围内。 */
    private static final String VIEW_FUNCTION = "lineage:view";

    private final LineageService lineageService;

    /** 表级血缘查询 */
    @GetMapping("/table/{tableName}")
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.DATASOURCE, resourceIds = "#datasourceId")
    public Result<List<LineageTableVO>> queryTableLineage(
            @RequestParam Long datasourceId, @PathVariable String tableName) {
        requireDatasourceId(datasourceId);
        return Result.success(lineageService.queryTableLineage(datasourceId, tableName));
    }

    /** 字段级血缘查询 */
    @GetMapping("/column/{tableName}/{columnName}")
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.DATASOURCE, resourceIds = "#datasourceId")
    public Result<List<LineageColumnVO>> queryColumnLineage(
            @RequestParam Long datasourceId, @PathVariable String tableName, @PathVariable String columnName) {
        requireDatasourceId(datasourceId);
        return Result.success(lineageService.queryColumnLineage(datasourceId, tableName, columnName));
    }

    /** 变更影响分析 */
    @GetMapping("/impact/{tableName}/{columnName}")
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.DATASOURCE, resourceIds = "#datasourceId")
    public Result<ImpactAnalysisVO> analyzeImpact(
            @RequestParam Long datasourceId, @PathVariable String tableName, @PathVariable String columnName) {
        requireDatasourceId(datasourceId);
        return Result.success(lineageService.analyzeImpact(datasourceId, tableName, columnName));
    }

    /** 表级变更影响分析 */
    @GetMapping("/impact/{tableName}")
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.DATASOURCE, resourceIds = "#datasourceId")
    public Result<ImpactAnalysisVO> analyzeTableImpact(
            @RequestParam Long datasourceId, @PathVariable String tableName) {
        requireDatasourceId(datasourceId);
        return Result.success(lineageService.analyzeImpact(datasourceId, tableName, null));
    }

    private void requireDatasourceId(Long datasourceId) {
        if (datasourceId == null) {
            throw new BusinessException("请选择数据源");
        }
    }
}
