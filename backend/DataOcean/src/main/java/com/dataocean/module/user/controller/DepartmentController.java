package com.dataocean.module.user.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.user.entity.dto.DepartmentCreateDTO;
import com.dataocean.module.user.entity.vo.DepartmentTreeVO;
import com.dataocean.module.user.service.DepartmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 部门管理控制器。
 * <p>
 * 提供部门树查询、创建和删除的 REST API 端点。
 * 同时映射管理端路径 /api/admin/departments 和通用路径 /api/departments。
 * </p>
 *
 * @author DataOcean
 */
@RestController
@RequestMapping({"/api/admin/departments", "/api/departments"})
@RequiredArgsConstructor
@Slf4j
public class DepartmentController {

    private final DepartmentService departmentService;

    /**
     * 查询部门树结构。
     * <p>
     * 返回所有启用状态的部门，按层级组装为树形结构。
     * 需要 department:manage 或 user:manage 权限。
     * </p>
     *
     * @return 部门树根节点列表
     */
    @GetMapping("/tree")
    @PreAuthorize("hasAnyAuthority('department:manage', 'user:manage')")
    public Result<List<DepartmentTreeVO>> tree() {
        log.debug("收到部门树查询请求");
        return Result.success(departmentService.tree());
    }

    /**
     * 创建新部门。
     * <p>
     * 需要 department:manage 权限。
     * </p>
     *
     * @param request 部门创建请求参数（含部门名称、编码、上级部门 ID 等）
     * @return 新创建部门的 ID
     */
    @PostMapping
    @PreAuthorize("hasAuthority('department:manage')")
    public Result<Map<String, Long>> createDepartment(@Valid @RequestBody DepartmentCreateDTO request) {
        log.debug("收到创建部门请求 deptCode={} parentId={}", request.getDeptCode(), request.getParentId());
        Long id = departmentService.createDepartment(request);
        return Result.success("创建成功", Map.of("id", id));
    }

    /**
     * 删除部门。
     * <p>
     * 仅允许删除空部门（无子部门且无关联用户）。
     * 需要 department:manage 权限。
     * </p>
     *
     * @param id 部门 ID
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('department:manage')")
    public Result<Void> deleteDepartment(@PathVariable Long id) {
        log.debug("收到删除部门请求 departmentId={}", id);
        departmentService.deleteDepartment(id);
        return Result.success("删除成功", null);
    }
}
