package com.dataocean.module.permission.s1.resource.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.mapper.MetadataEntityMapper;
import com.dataocean.module.metadata.mapper.MetadataRelationshipMapper;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolver;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * LINEAGE_RELATIONSHIP：血缘关系（{@code metadata_relationship} 行）的真实归属。
 *
 * <p>归属链：关系 → 源实体 {@code entity_metadata.datasource_id}。</p>
 *
 * <p>删除接口只收到关系 ID。若只按请求里携带的数据源判定，调用者可以用一个自己
 * 负责的源去删别人源上的边。关系不存在返回 404；源实体不存在或没有数据源归属返回 409。
 * 目标实体的归属由 Service 在写入前另行校验，不能只信这里返回的源端数据源。</p>
 */
@Component
@RequiredArgsConstructor
public class LineageRelationshipResourceResolver implements IamS1ResourceResolver {

    private final MetadataRelationshipMapper relationshipMapper;
    private final MetadataEntityMapper entityMapper;

    @Override
    public IamS1ResourceType supports() {
        return IamS1ResourceType.LINEAGE_RELATIONSHIP;
    }

    @Override
    public IamS1ResolvedResource resolve(Object resourceId) {
        Long id = IamS1ResourceIds.asLong(resourceId);
        if (id == null) {
            throw new BusinessException(400, "缺少血缘关系参数，无法判定负责范围");
        }
        MetadataRelationship relationship = relationshipMapper.selectById(id);
        if (relationship == null) {
            throw new BusinessException(404, "血缘关系不存在");
        }
        if (relationship.getSourceId() == null) {
            throw new BusinessException(409, "血缘关系缺少源实体，无法判定负责范围");
        }
        Long datasourceId = entityMapper.selectDatasourceIdByEntityId(relationship.getSourceId());
        if (datasourceId == null) {
            throw new BusinessException(409, "血缘关系的源实体不存在或没有数据源归属，无法判定负责范围");
        }
        return IamS1ResolvedResource.of(IamS1ResourceType.LINEAGE_RELATIONSHIP, id, datasourceId);
    }
}
