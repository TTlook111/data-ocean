package com.dataocean.module.iam.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.iam.entity.query.DepartmentOptionQuery;
import com.dataocean.module.iam.entity.query.RoleOptionQuery;
import com.dataocean.module.iam.entity.query.UserOptionQuery;
import com.dataocean.module.iam.entity.vo.DepartmentOptionVO;
import com.dataocean.module.iam.entity.vo.RoleOptionVO;
import com.dataocean.module.iam.entity.vo.UserOptionVO;
import com.dataocean.module.iam.security.IamRequestPermission;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.iam.service.UserService;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * IdentityOption 的类型化 HTTP 入口，业务核验委托所属服务。
 */
@RestController
@RequestMapping("/api/v1/iam")
@RequiredArgsConstructor
public class IdentityOptionController {

    /**
     * 当前模块的业务服务。
     */
    private final UserService service;

    /**
     * 读取本次动作可选择或只读保留的部门。
     */
    @GetMapping("/department-options")
    @RequirePermission(policy = IamRequestPermission.class)
    public Result<List<DepartmentOptionVO>> departments(@Valid @ModelAttribute DepartmentOptionQuery query) {
        return Result.success(service.departmentOptions(query));
    }

    /**
     * 读取完整能力在操作者上限内的角色。
     */
    @GetMapping("/role-options")
    @RequirePermission(policy = IamRequestPermission.class)
    public Result<List<RoleOptionVO>> roles(@Valid @ModelAttribute RoleOptionQuery query) {
        return Result.success(service.roleOptions(query));
    }

    /**
     * 读取部门负责人或兼任加入的可选人员。
     */
    @GetMapping("/user-options")
    @RequirePermission(policy = IamRequestPermission.class)
    public Result<List<UserOptionVO>> users(@Valid @ModelAttribute UserOptionQuery query) {
        return Result.success(service.userOptions(query));
    }
}
