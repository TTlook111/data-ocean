package com.dataocean.module.permission.s1.resource.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.entity.IamS1DatasourceFact;
import com.dataocean.module.permission.s1.mapper.IamS1DatasourceIdentityMapper;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolver;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** DATASOURCE：直接读取未删除的数据源身份事实。 */
@Component
@RequiredArgsConstructor
public class DatasourceResourceResolver implements IamS1ResourceResolver {

    private final IamS1DatasourceIdentityMapper datasourceIdentityMapper;

    @Override
    public IamS1ResourceType supports() {
        return IamS1ResourceType.DATASOURCE;
    }

    @Override
    public IamS1ResolvedResource resolve(Object resourceId) {
        Long id = IamS1ResourceIds.asLong(resourceId);
        if (id == null) {
            throw new BusinessException(400, "缺少数据源参数，无法判定负责范围");
        }
        IamS1DatasourceFact fact = datasourceIdentityMapper.selectIdentity(id);
        if (fact == null || fact.getId() == null) {
            // 不存在或已删除：归属断链，fail-closed。
            throw new BusinessException(404, "数据源不存在或已删除");
        }
        return IamS1ResolvedResource.of(IamS1ResourceType.DATASOURCE, fact.getId(), fact.getId());
    }
}
