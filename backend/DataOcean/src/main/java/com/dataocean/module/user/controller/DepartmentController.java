package com.dataocean.module.user.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.user.entity.req.DepartmentCreateRequest;
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

@RestController
@RequestMapping({"/api/admin/departments", "/api/departments"})
@RequiredArgsConstructor
@Slf4j
public class DepartmentController {

    private final DepartmentService departmentService;

    @GetMapping("/tree")
    @PreAuthorize("hasAnyAuthority('department:manage', 'user:manage')")
    public Result<List<DepartmentTreeVO>> tree() {
        log.debug("收到部门树查询请求");
        return Result.success(departmentService.tree());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('department:manage')")
    public Result<Map<String, Long>> createDepartment(@Valid @RequestBody DepartmentCreateRequest request) {
        log.debug("收到创建部门请求 deptCode={} parentId={}", request.getDeptCode(), request.getParentId());
        Long id = departmentService.createDepartment(request);
        return Result.success("创建成功", Map.of("id", id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('department:manage')")
    public Result<Void> deleteDepartment(@PathVariable Long id) {
        log.debug("收到删除部门请求 departmentId={}", id);
        departmentService.deleteDepartment(id);
        return Result.success("删除成功", null);
    }
}
