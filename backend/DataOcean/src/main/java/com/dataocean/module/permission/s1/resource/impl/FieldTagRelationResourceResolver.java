package com.dataocean.module.permission.s1.resource.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.fieldtag.entity.FieldTag;
import com.dataocean.module.fieldtag.mapper.FieldTagMapper;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolver;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * FIELD_TAG_RELATION：字段标签关系（`field_tag` 行）的真实归属。
 *
 * <p>归属链：标签关系 → `column_meta_id` → `db_column_meta.datasource_id`。</p>
 *
 * <p>为什么删除标签必须解析**关系自身**的归属：删除接口只收到关系 ID，
 * 若按请求里携带的数据源判定，调用者可以用一个自己负责的源去删别人源上的标签。
 * 关系或列不存在返回 404，列的归属缺失返回 409。</p>
 */
@Component
@RequiredArgsConstructor
public class FieldTagRelationResourceResolver implements IamS1ResourceResolver {

    private final FieldTagMapper fieldTagMapper;
    private final DbColumnMetaMapper columnMetaMapper;

    @Override
    public IamS1ResourceType supports() {
        return IamS1ResourceType.FIELD_TAG_RELATION;
    }

    @Override
    public IamS1ResolvedResource resolve(Object resourceId) {
        Long id = IamS1ResourceIds.asLong(resourceId);
        if (id == null) {
            throw new BusinessException(400, "缺少字段标签参数，无法判定负责范围");
        }
        FieldTag tag = fieldTagMapper.selectById(id);
        if (tag == null) {
            throw new BusinessException(404, "字段标签不存在");
        }
        if (tag.getColumnMetaId() == null) {
            throw new BusinessException(409, "字段标签缺少字段归属，无法判定负责范围");
        }
        DbColumnMeta column = columnMetaMapper.selectById(tag.getColumnMetaId());
        if (column == null) {
            throw new BusinessException(404, "字段标签关联的字段不存在");
        }
        if (column.getDatasourceId() == null) {
            throw new BusinessException(409, "字段标签关联的字段缺少数据源归属，无法判定负责范围");
        }
        return new IamS1ResolvedResource(IamS1ResourceType.FIELD_TAG_RELATION, id,
                column.getDatasourceId(), column.getSnapshotId(), column.getTableName(), column.getColumnName());
    }
}
