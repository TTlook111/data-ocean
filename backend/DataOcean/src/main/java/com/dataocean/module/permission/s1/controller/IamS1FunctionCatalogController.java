package com.dataocean.module.permission.s1.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.catalog.IamS1FunctionCatalog;
import com.dataocean.module.permission.s1.entity.IamS1Function;
import com.dataocean.module.permission.s1.entity.vo.IamS1FunctionCatalogVO;
import com.dataocean.module.permission.s1.mapper.IamS1FunctionMapper;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IAM-SIMPLE-1 固定功能目录（只读）。
 * <p>
 * 目录只允许由前向 migration 初始化；本接口不提供新增、编辑或删除功能点，
 * 也不允许管理员创建任意字符串功能码。
 * </p>
 */
@RestController
@RequestMapping("/api/iam-s1/functions")
@RequiredArgsConstructor
public class IamS1FunctionCatalogController {

    private final IamS1FunctionMapper functionMapper;
    private final IamS1AdminGuard adminGuard;

    /** 全部固定功能条目；可按业务域过滤。 */
    @GetMapping
    public Result<List<IamS1FunctionCatalogVO>> list(@RequestParam(required = false) String domain) {
        adminGuard.requireGlobalFunction(UserContext.currentUserId(), "organization:permission:view");
        Map<String, String> statusByCode = new LinkedHashMap<>();
        for (IamS1Function function : functionMapper.selectList(
                new LambdaQueryWrapper<IamS1Function>().orderByAsc(IamS1Function::getId))) {
            statusByCode.put(function.getFunctionCode(), function.getStatus());
        }
        String normalizedDomain = domain == null || domain.isBlank() ? null : domain.trim();
        List<IamS1FunctionCatalogVO> result = new ArrayList<>();
        for (IamS1FunctionCatalog.Definition definition : IamS1FunctionCatalog.definitions()) {
            if (normalizedDomain != null && !normalizedDomain.equals(definition.domain())) {
                continue;
            }
            result.add(new IamS1FunctionCatalogVO(definition.code(), definition.name(),
                    definition.description(), definition.domain(), definition.workspace(),
                    definition.dependencies(),
                    IamS1FunctionCatalog.requiresSystemAdminRoleConfiguration(definition.code()),
                    IamS1Constants.FUNCTION_STATUS_ACTIVE.equals(statusByCode.get(definition.code()))));
        }
        return Result.success(result);
    }
}
