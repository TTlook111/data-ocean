package com.dataocean.module.permission.s1.resource.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.mapper.MetadataEntityMapper;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolver;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * METADATA_COLUMN：列实体 ID → snapshot / datasource / table。
 *
 * <p>列实体必须是 {@code COLUMN} 类型：同一 ID 空间里还有 TABLE、TAG 等实体，
 * 不校验类型会让“用表实体 ID 冒充列”绕过列级语义。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataColumnResourceResolver implements IamS1ResourceResolver {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final MetadataEntityMapper entityMapper;

    @Override
    public IamS1ResourceType supports() {
        return IamS1ResourceType.METADATA_COLUMN;
    }

    @Override
    public IamS1ResolvedResource resolve(Object resourceId) {
        Long id = IamS1ResourceIds.asLong(resourceId);
        if (id == null) {
            throw new BusinessException(400, "缺少字段参数，无法判定负责范围");
        }
        MetadataEntity entity = entityMapper.selectById(id);
        if (entity == null || !MetadataEntity.TYPE_COLUMN.equals(entity.getEntityType())) {
            throw new BusinessException(404, "字段实体不存在");
        }

        Long datasourceId = null;
        Long snapshotId = null;
        try {
            JsonNode node = OBJECT_MAPPER.readTree(entity.getEntityMetadata());
            if (node != null && node.hasNonNull("datasource_id")) {
                datasourceId = node.get("datasource_id").asLong();
            }
            if (node != null && node.hasNonNull("snapshot_id")) {
                snapshotId = node.get("snapshot_id").asLong();
            }
        } catch (Exception exception) {
            log.warn("解析字段实体归属失败 entityId={}: {}", id, exception.getMessage());
        }
        if (datasourceId == null) {
            throw new BusinessException(404, "字段实体没有数据源归属");
        }

        // FQN 形如 datasource.db.table.column → 取倒数第二段作为表名
        String fqn = entity.getFqn();
        String[] parts = fqn == null ? new String[0] : fqn.split("\\.");
        String tableName = parts.length >= 3 ? parts[parts.length - 2] : null;

        return new IamS1ResolvedResource(IamS1ResourceType.METADATA_COLUMN, id,
                datasourceId, snapshotId, tableName, entity.getName());
    }
}
