package com.dataocean.module.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.system.entity.SysOperationLog;
import com.dataocean.module.system.aspect.AdminAuditLog;
import com.dataocean.module.system.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 操作日志控制器
 */
@RestController
@RequestMapping("/api/admin/operation-logs")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('audit:view')")
@AdminAuditLog
public class OperationLogController {

    private final OperationLogService operationLogService;

    /**
     * 分页查询操作日志
     *
     * @param page           页码
     * @param pageSize       每页大小
     * @param targetResource 目标资源过滤（可选）
     */
    @GetMapping
    public Result<Page<SysOperationLog>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String targetResource) {
        return Result.success(operationLogService.listLogs(page, pageSize, targetResource));
    }
}
