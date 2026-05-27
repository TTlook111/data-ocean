package com.dataocean.module.permission.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.permission.entity.dto.AccessPolicyBatchDTO;
import com.dataocean.module.permission.entity.dto.AccessPolicyCreateDTO;
import com.dataocean.module.permission.entity.vo.AccessPolicyVO;
import com.dataocean.module.permission.service.AccessPolicyService;
import com.dataocean.module.system.aspect.AdminAuditLog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 行列级访问策略管理控制器
 * <p>
 * 提供细粒度的表级、列级、行级访问控制策略的管理接口。
 * </p>
 *
 * @author dataocean
 */
@RestController
@RequestMapping("/api/admin/access-policies")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('security:manage', '*')")
@AdminAuditLog
public class AccessPolicyController {

    private final AccessPolicyService policyService;

    /**
     * 创建策略
     */
    @PostMapping
    public Result<Map<String, Long>> create(@Valid @RequestBody AccessPolicyCreateDTO dto) {
        Long id = policyService.create(dto);
        return Result.success("策略创建成功", Map.of("id", id));
    }

    /**
     * 批量创建策略
     */
    @PostMapping("/batch")
    public Result<Void> batchCreate(@Valid @RequestBody AccessPolicyBatchDTO dto) {
        int count = policyService.batchCreate(dto);
        return Result.success("批量创建成功，共 " + count + " 条策略", null);
    }

    /**
     * 更新策略
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        policyService.update(id,
                body.get("accessType"),
                body.get("maskStrategy"),
                body.get("rowFilterExpression"));
        return Result.success("策略更新成功", null);
    }

    /**
     * 删除策略
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        policyService.delete(id);
        return Result.success("策略已删除", null);
    }

    /**
     * 查询策略列表
     */
    @GetMapping
    public Result<List<AccessPolicyVO>> list(
            @RequestParam Long datasourceId,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) String tableName) {
        return Result.success(policyService.list(datasourceId, subjectType, subjectId, tableName));
    }
}
