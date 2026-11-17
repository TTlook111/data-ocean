package com.dataocean.module.audit.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.audit.entity.query.AuditQuery;
import com.dataocean.module.audit.entity.vo.AuditDetailVO;
import com.dataocean.module.audit.entity.vo.AuditRowVO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.iam.service.PermissionService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 只读审计入口，记录和数量使用同一页面权限。
 */
@RestController
@RequestMapping("/api/v1/iam/audit-records")
@RequiredArgsConstructor
public class AuditController {

    /**
     * 审计查询及历史身份快照读取。
     */
    private final AuditService audits;

    /**
     * 读取审计前的当前功能权限检查。
     */
    private final PermissionService permissions;

    /**
     * 按历史操作者、目标和实际发生时间查询审计。
     */
    @GetMapping
    public Result<PageVO<AuditRowVO>> list(@Valid @ModelAttribute AuditQuery query) {
        permissions.require("audit.view");
        return Result.success(audits.list(query));
    }

    /**
     * 只读查看审计差异，不在此入口开放原文资源。
     */
    @GetMapping("/{id}")
    public Result<AuditDetailVO> detail(@PathVariable String id) {
        permissions.require("audit.view");
        return Result.success(audits.detail(Ids.parse(id)));
    }
}
