package com.dataocean.module.permission.s1.resource.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.metadata.mapper.MetadataEntityMapper;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolver;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * METADATA_ENTITY：entityId → entity_metadata.datasource_id。
 *
 * <p>元数据实体本身没有 datasourceId 字段，归属存在 `entity_metadata` JSON 里，
 * 所以必须回查真实归属，不能只看实体是否存在。</p>
 */
@Component
@RequiredArgsConstructor
public class MetadataEntityResourceResolver implements IamS1ResourceResolver {

    private final MetadataEntityMapper entityMapper;

    @Override
    public IamS1ResourceType supports() {
        return IamS1ResourceType.METADATA_ENTITY;
    }

    @Override
    public IamS1ResolvedResource resolve(Object resourceId) {
        Long id = IamS1ResourceIds.asLong(resourceId);
        if (id == null) {
            throw new BusinessException(400, "缺少元数据实体参数，无法判定负责范围");
        }
        Long datasourceId = entityMapper.selectDatasourceIdByEntityId(id);
        if (datasourceId == null) {
            // 实体不存在，或存在但没有数据源归属：两种都 fail-closed。
            throw new BusinessException(404, "元数据实体不存在或没有数据源归属");
        }
        return IamS1ResolvedResource.of(IamS1ResourceType.METADATA_ENTITY, id, datasourceId);
    }
}
