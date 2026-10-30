package com.dataocean.module.permission.s1.support;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.catalog.IamS1FunctionCatalog;
import com.dataocean.module.permission.s1.entity.vo.IamS1AuthorizationDecision;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * IAM-SIMPLE-1 后台接口的服务端强制校验入口。
 * <p>
 * 规则冻结：前端隐藏路由、Tab 或按钮不能替代后端校验；直接调用 API 一律经过本类判定。
 * 判定只读取 S1 功能目录、S1 角色关系和账号/数据源身份状态，不读取旧角色、旧权限码、旧数据授权或旧缓存。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class IamS1AdminGuard {

    private final IamS1AuthorizationResolver authorizationResolver;

    /** 全局功能：不依赖数据源负责范围。 */
    public void requireGlobalFunction(Long operatorUserId, String functionCode) {
        if (operatorUserId == null) {
            throw new BusinessException(401, "未登录，无法判定 IAM-SIMPLE-1 权限");
        }
        if (!authorizationResolver.hasGlobalFunction(operatorUserId, functionCode)) {
            throw new BusinessException(403, IamS1ReasonMessages.adminDenyMessage(
                    functionName(functionCode), "FUNCTION_NOT_GRANTED"));
        }
    }

    /** 数据源范围内功能：功能与负责源必须在同一个启用的用户角色绑定上同时成立。 */
    public void requireDatasourceFunction(Long operatorUserId, String functionCode, Long datasourceId) {
        if (operatorUserId == null) {
            throw new BusinessException(401, "未登录，无法判定 IAM-SIMPLE-1 权限");
        }
        if (datasourceId == null) {
            throw new BusinessException(400, "缺少数据源参数，无法判定 IAM-SIMPLE-1 后台范围");
        }
        IamS1AuthorizationDecision decision =
                authorizationResolver.resolveAdminAction(operatorUserId, functionCode, datasourceId);
        if (!decision.isAllowed()) {
            throw new BusinessException(403, IamS1ReasonMessages.adminDenyMessage(
                    functionName(functionCode), decision.getReasonCode()));
        }
    }

    public boolean isSystemAdmin(Long operatorUserId) {
        return authorizationResolver.isSystemAdmin(operatorUserId);
    }

    public static String functionName(String functionCode) {
        IamS1FunctionCatalog.Definition definition = IamS1FunctionCatalog.find(functionCode);
        return definition == null ? functionCode : definition.name();
    }
}
