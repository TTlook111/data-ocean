package com.dataocean.module.permission.s1.service;

import com.dataocean.module.permission.s1.entity.vo.IamS1AuthorizationDecision;

/** IAM-SIMPLE-1 默认拒绝的功能和后台负责源 Resolver。 */
public interface IamS1AuthorizationResolver {

    boolean hasGlobalFunction(Long userId, String functionCode);

    boolean canManageDatasource(Long userId, String functionCode, Long datasourceId);

    boolean isSystemAdmin(Long userId);

    IamS1AuthorizationDecision resolveAdminAction(Long userId, String functionCode, Long datasourceId);
}
