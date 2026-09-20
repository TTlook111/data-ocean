package com.dataocean.module.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.permission.s1.annotation.IamS1Global;
import com.dataocean.module.system.entity.SysOperationLog;
import com.dataocean.module.system.entity.dto.OperationLogQueryDTO;
import com.dataocean.module.system.service.OperationLogService;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

/**
 * 操作日志控制器
 */
@RestController
@RequestMapping("/api/admin/operation-logs")
@RequiredArgsConstructor
public class OperationLogController {

    /** 操作日志是独立的只读功能：拥有 audit:view 不会自动获得操作日志权限。 */
    private static final String VIEW_FUNCTION = "operation-log:view";

    private final OperationLogService operationLogService;

    /**
     * 分页查询操作日志，支持操作人/类型/状态/时间范围/IP/路径/目标资源/目标ID/关键词多条件筛选
     */
    @GetMapping
    @IamS1Global(VIEW_FUNCTION)
    public Result<Page<SysOperationLog>> list(@ModelAttribute OperationLogQueryDTO query) {
        return Result.success(operationLogService.listLogs(query));
    }
}
