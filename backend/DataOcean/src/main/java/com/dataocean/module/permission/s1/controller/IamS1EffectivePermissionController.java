package com.dataocean.module.permission.s1.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.s1.entity.dto.IamS1DataAuthorizationRequestDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot;
import com.dataocean.module.permission.s1.service.IamS1EffectivePermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IAM-SIMPLE-1 用户实际权限预览。
 * <p>
 * 该接口与真实查询共用同一个统一 Resolver；结果包含允许的表、字段、记录条件摘要、
 * 字段保护、授权来源和拒绝原因，不在前端自行合并权限。
 * </p>
 */
@RestController
@RequestMapping("/api/iam-s1/effective-permissions")
@RequiredArgsConstructor
public class IamS1EffectivePermissionController {

    private final IamS1EffectivePermissionService effectivePermissionService;

    @PostMapping("/preview")
    public Result<IamS1DataAuthorizationSnapshot> preview(
            @RequestBody IamS1DataAuthorizationRequestDTO request) {
        return Result.success(effectivePermissionService.preview(UserContext.currentUserId(), request));
    }
}
