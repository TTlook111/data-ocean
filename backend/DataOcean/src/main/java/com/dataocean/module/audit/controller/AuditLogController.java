package com.dataocean.module.audit.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.audit.entity.dto.AuditLogQueryDTO;
import com.dataocean.module.audit.entity.vo.AuditLogVO;
import com.dataocean.module.audit.entity.vo.AuditStatsVO;
import com.dataocean.module.audit.service.AuditLogService;
import com.dataocean.module.system.aspect.AdminAuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
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
@PreAuthorize("hasAuthority('audit:view')")
@AdminAuditLog
@Slf4j
public class AuditLogController {

    private final AuditLogService auditLogService;

    /** 多维度筛选分页查询 */
    @GetMapping
    public Result<Page<AuditLogVO>> listAuditLogs(@ModelAttribute AuditLogQueryDTO query) {
        return Result.success(auditLogService.listAuditLogs(query));
    }

    /** 审计日志详情 */
    @GetMapping("/{id}")
    public Result<AuditLogVO> getDetail(@PathVariable Long id) {
        return Result.success(auditLogService.getAuditLogDetail(id));
    }

    /** 慢查询列表 */
    @GetMapping("/slow-queries")
    public Result<Page<AuditLogVO>> listSlowQueries(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(auditLogService.listSlowQueries(page, pageSize));
    }

    /** 审计统计 */
    @GetMapping("/stats")
    public Result<AuditStatsVO> getStats(
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(defaultValue = "30") int days) {
        return Result.success(auditLogService.getStats(datasourceId, days));
    }

    /** 将查询提升为模板 */
    @PostMapping("/{id}/promote-template")
    public Result<Void> promoteTemplate(@PathVariable Long id) {
        auditLogService.promoteToTemplate(id);
        return Result.success("已提升为模板", null);
    }
}
