package com.dataocean.module.permission.s1.service;

import java.util.Collection;

/** IAM-SIMPLE-1 用户角色负责数据源服务。 */
public interface IamS1RoleDatasourceService {

    void bindDatasources(Long operatorUserId, Long userRoleId, Collection<Long> datasourceIds, String reason);

    void removeDatasource(Long operatorUserId, Long userRoleId, Long datasourceId, String reason);
}
