package com.dataocean.module.permission.s1.resource.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolver;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * COLUMN_META：字段元数据（列）归属的数据源。
 *
 * <p>`db_column_meta` 上直接存有 `datasource_id` 与 `snapshot_id`，是字段治理读写的统一归属入口。
 * 列不存在返回 404、归属缺失返回 409。</p>
 *
 * <p>为什么字段治理不能只靠实体元数据（`metadata_entity`）：标签、置信度事件与反馈审核
 * 都以 `column_meta_id` 为主键，直接读列行才是这些接口的真实目标对象。</p>
 */
@Component
@RequiredArgsConstructor
public class ColumnMetaResourceResolver implements IamS1ResourceResolver {

    private final DbColumnMetaMapper columnMetaMapper;

    @Override
    public IamS1ResourceType supports() {
        return IamS1ResourceType.COLUMN_META;
    }

    @Override
    public IamS1ResolvedResource resolve(Object resourceId) {
        Long id = IamS1ResourceIds.asLong(resourceId);
        if (id == null) {
            throw new BusinessException(400, "缺少字段参数，无法判定负责范围");
        }
        DbColumnMeta column = columnMetaMapper.selectById(id);
        if (column == null) {
            throw new BusinessException(404, "字段不存在");
        }
        if (column.getDatasourceId() == null) {
            throw new BusinessException(409, "字段缺少数据源归属，无法判定负责范围");
        }
        return new IamS1ResolvedResource(IamS1ResourceType.COLUMN_META, id,
                column.getDatasourceId(), column.getSnapshotId(), column.getTableName(), column.getColumnName());
    }
}
