package com.dataocean.module.permission.s1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.catalog.IamS1FunctionCatalog;
import com.dataocean.module.permission.s1.entity.IamS1Function;
import com.dataocean.module.permission.s1.mapper.IamS1FunctionMapper;
import com.dataocean.module.permission.s1.service.IamS1FunctionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/** IAM-SIMPLE-1 固定目录服务实现。 */
@Service
@RequiredArgsConstructor
public class IamS1FunctionServiceImpl implements IamS1FunctionService {

    private final IamS1FunctionMapper functionMapper;

    @Override
    public List<IamS1Function> resolveExpanded(Collection<String> functionCodes) {
        List<String> expandedCodes;
        try {
            expandedCodes = IamS1FunctionCatalog.expand(functionCodes);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new BusinessException(exception.getMessage());
        }
        if (expandedCodes.isEmpty()) {
            return List.of();
        }

        List<IamS1Function> functions = functionMapper.selectList(new LambdaQueryWrapper<IamS1Function>()
                .in(IamS1Function::getFunctionCode, expandedCodes)
                .eq(IamS1Function::getStatus, "ACTIVE"));
        if (functions.size() != expandedCodes.size()) {
            throw new BusinessException("固定功能目录不完整，请先完成 IAM-SIMPLE-1 migration");
        }

        return expandedCodes.stream()
                .map(code -> functions.stream()
                        .filter(function -> code.equals(function.getFunctionCode()))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException("固定功能目录缺少: " + code)))
                .toList();
    }
}
