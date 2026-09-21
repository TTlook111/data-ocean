package com.dataocean.module.permission.s1.resource.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolver;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** SNAPSHOT：snapshotId → datasourceId。 */
@Component
@RequiredArgsConstructor
public class SnapshotResourceResolver implements IamS1ResourceResolver {

    private final MetadataSnapshotMapper snapshotMapper;

    @Override
    public IamS1ResourceType supports() {
        return IamS1ResourceType.SNAPSHOT;
    }

    @Override
    public IamS1ResolvedResource resolve(Object resourceId) {
        Long id = IamS1ResourceIds.asLong(resourceId);
        if (id == null) {
            throw new BusinessException(400, "缺少快照参数，无法判定负责范围");
        }
        MetadataSnapshot snapshot = snapshotMapper.selectById(id);
        if (snapshot == null) {
            throw new BusinessException(404, "快照不存在");
        }
        if (snapshot.getDatasourceId() == null) {
            // 归属断链：不能当成“无归属即可放行”。
            throw new BusinessException(409, "快照缺少数据源归属，无法判定负责范围");
        }
        return new IamS1ResolvedResource(IamS1ResourceType.SNAPSHOT, id,
                snapshot.getDatasourceId(), id, null, null);
    }
}
