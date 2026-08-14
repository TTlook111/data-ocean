package com.dataocean.module.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.system.entity.SysOperationLog;
import com.dataocean.module.system.entity.dto.OperationLogQueryDTO;
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
public class OperationLogController {

    private final OperationLogService operationLogService;

    /**
     * 分页查询操作日志，支持操作人/类型/状态/时间范围/IP/路径/目标资源/目标ID/关键词多条件筛选
     */
    @GetMapping
    public Result<Page<SysOperationLog>> list(@ModelAttribute OperationLogQueryDTO query) {
        return Result.success(operationLogService.listLogs(query));
    }
}
