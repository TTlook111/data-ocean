package com.dataocean.module.user.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.user.dto.DepartmentCreateRequest;
import com.dataocean.module.user.dto.DepartmentTreeVO;
import com.dataocean.module.user.service.DepartmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
public class DepartmentController {

    private final DepartmentService departmentService;

    @GetMapping("/tree")
    @PreAuthorize("hasAnyAuthority('department:manage', 'user:manage')")
    public Result<List<DepartmentTreeVO>> tree() {
        return Result.success(departmentService.tree());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('department:manage')")
    public Result<Map<String, Long>> createDepartment(@Valid @RequestBody DepartmentCreateRequest request) {
        Long id = departmentService.createDepartment(request);
        return Result.success("创建成功", Map.of("id", id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('department:manage')")
    public Result<Void> deleteDepartment(@PathVariable Long id) {
        departmentService.deleteDepartment(id);
        return Result.success("删除成功", null);
    }
}
