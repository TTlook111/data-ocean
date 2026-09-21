package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1Function;
import com.dataocean.module.permission.s1.entity.vo.IamS1AuthorizationDecision;
import com.dataocean.module.permission.s1.mapper.IamS1DatasourceIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1FunctionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserIdentityMapper;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * IAM-SIMPLE-1 功能/后台负责源 Resolver。
 * <p>
 * 该类只依赖 S1 关系和账号、数据源的身份状态；没有旧权限输入，也不执行业务数据授权判断。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class IamS1AuthorizationResolverImpl implements IamS1AuthorizationResolver {

    private final IamS1UserIdentityMapper userIdentityMapper;
    private final IamS1DatasourceIdentityMapper datasourceIdentityMapper;
    private final IamS1FunctionMapper functionMapper;
    private final IamS1RoleMapper roleMapper;

    @Override
    public boolean hasGlobalFunction(Long userId, String functionCode) {
        if (userId == null || functionCode == null || functionCode.isBlank()
                || !isEnabledUser(userId)) {
            return false;
        }
        IamS1Function function = functionMapper.selectActiveByCode(functionCode);
        if (function == null) {
            return false;
        }
        if (isSystemAdmin(userId)) {
            return true;
        }
        return roleMapper.countActiveUserFunction(userId, functionCode) > 0;
    }

    @Override
    public boolean canManageDatasource(Long userId, String functionCode, Long datasourceId) {
        return resolveAdminAction(userId, functionCode, datasourceId).isAllowed();
    }

    @Override
    public boolean isSystemAdmin(Long userId) {
        if (userId == null || !isEnabledUser(userId)) {
            return false;
        }
        // 受保护角色必须唯一；出现异常重复时安全拒绝，不能扩大后台范围。
        return roleMapper.countActiveProtectedRoles() == 1
                && roleMapper.countActiveProtectedBindings(userId) == 1;
    }

    @Override
    public IamS1AuthorizationDecision resolveAdminAction(Long userId, String functionCode, Long datasourceId) {
        if (userId == null || functionCode == null || functionCode.isBlank() || datasourceId == null) {
            return IamS1AuthorizationDecision.deny("MISSING_REQUIRED_PARAMETER", functionCode, datasourceId);
        }
        if (!isEnabledUser(userId)) {
            return IamS1AuthorizationDecision.deny("USER_NOT_ENABLED", functionCode, datasourceId);
        }
        IamS1Function function = functionMapper.selectActiveByCode(functionCode);
        if (function == null) {
            return IamS1AuthorizationDecision.deny("UNKNOWN_FUNCTION", functionCode, datasourceId);
        }
        if (!isEnabledDatasource(datasourceId)) {
            return IamS1AuthorizationDecision.deny("DATASOURCE_NOT_ENABLED", functionCode, datasourceId);
        }
        if (isSystemAdmin(userId)) {
            return IamS1AuthorizationDecision.allow(functionCode, datasourceId);
        }

        if (roleMapper.countActiveUserRoleBindings(userId) == 0) {
            return IamS1AuthorizationDecision.deny("NO_S1_BINDING", functionCode, datasourceId);
        }
        if (roleMapper.countActiveUserFunction(userId, functionCode) == 0) {
            return IamS1AuthorizationDecision.deny("FUNCTION_NOT_GRANTED", functionCode, datasourceId);
        }
        if (roleMapper.countActiveUserDatasource(userId, datasourceId) == 0) {
            return IamS1AuthorizationDecision.deny("DATASOURCE_NOT_ASSIGNED", functionCode, datasourceId);
        }
        if (roleMapper.countActiveUserFunctionDatasource(userId, functionCode, datasourceId) == 0) {
            return IamS1AuthorizationDecision.deny("SAME_BINDING_REQUIRED", functionCode, datasourceId);
        }
        return IamS1AuthorizationDecision.allow(functionCode, datasourceId);
    }

    private boolean isEnabledUser(Long userId) {
        return userIdentityMapper.countEnabledUser(userId) == 1;
    }

    private boolean isEnabledDatasource(Long datasourceId) {
        return datasourceIdentityMapper.countEnabledDatasource(datasourceId) == 1;
    }
}
