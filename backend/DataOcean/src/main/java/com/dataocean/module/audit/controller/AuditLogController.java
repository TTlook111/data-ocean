package com.dataocean.module.audit.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.annotation.IamS1Resource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.permission.s1.annotation.IamS1ScopedList;
import com.dataocean.module.audit.entity.dto.AuditLogQueryDTO;
import com.dataocean.module.audit.entity.vo.AuditLogVO;
import com.dataocean.module.audit.entity.vo.AuditStatsVO;
import com.dataocean.module.audit.service.AuditLogService;
import com.dataocean.module.system.aspect.AdminAuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.*;

/**
 * 审计日志控制器
 * <p>
 * 提供审计日志的查询、统计、慢查询列表和模板提升 API。
 * </p>
 */
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
@AdminAuditLog
@Slf4j
public class AuditLogController {

    /** 查看查询审计：负责源范围内的记录、慢查询与统计。 */
    private static final String VIEW_FUNCTION = "audit:view";

    private final AuditLogService auditLogService;
    private final IamS1CapabilityService capabilityService;

    /** 调用者在 `audit:view` 上负责的数据源 ID；空列表表示没有任何负责源。 */
    private java.util.List<Long> visibleDatasourceIds() {
        return capabilityService
                .responsibleDatasourcesWithFunction(UserContext.currentUserId(), VIEW_FUNCTION)
                .stream()
                .map(IamS1DatasourceRefVO::id)
                .toList();
    }

    /** 多维度筛选分页查询 */
    @GetMapping
    @IamS1ScopedList(VIEW_FUNCTION)
    public Result<Page<AuditLogVO>> listAuditLogs(@ModelAttribute AuditLogQueryDTO query) {
        // 负责源范围下推 SQL；空范围返回空页，显式筛选无权数据源时 Service 抛 403。
        return Result.success(auditLogService.listAuditLogsInDatasources(query, visibleDatasourceIds()));
    }

    /** 审计日志详情 */
    @GetMapping("/{id}")
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.AUDIT_LOG, resourceIds = "#id")
    public Result<AuditLogVO> getDetail(@PathVariable Long id) {
        return Result.success(auditLogService.getAuditLogDetail(id));
    }

    /** 慢查询列表 */
    @GetMapping("/slow-queries")
    @IamS1ScopedList(VIEW_FUNCTION)
    public Result<Page<AuditLogVO>> listSlowQueries(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(auditLogService.listSlowQueriesInDatasources(page, pageSize, visibleDatasourceIds()));
    }

    /** 审计统计 */
    @GetMapping("/stats")
    @IamS1ScopedList(VIEW_FUNCTION)
    public Result<AuditStatsVO> getStats(
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(defaultValue = "30") int days) {
        return Result.success(auditLogService.getStatsInDatasources(datasourceId, days, visibleDatasourceIds()));
    }

}
