package com.dataocean.module.metadata.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.mapper.MetadataEntityMapper;
import com.dataocean.module.metadata.service.MetadataEntityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 统一实体服务实现
 *
 * @author dataocean
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataEntityServiceImpl extends ServiceImpl<MetadataEntityMapper, MetadataEntity>
        implements MetadataEntityService {

    @Override
    public MetadataEntity getByFqn(String fqn) {
        return baseMapper.selectByFqn(fqn);
    }

    @Override
    public List<MetadataEntity> getTablesByDatasource(Long datasourceId, String datasourceName, String databaseName) {
        String fqnPrefix = MetadataEntity.fqnTable(datasourceName, databaseName, "").replaceAll("\\.$", "");
        // 如果没有 databaseName，只用 datasourceName 作为前缀
        if (databaseName == null || databaseName.isBlank()) {
            fqnPrefix = datasourceName.toLowerCase();
        }
        return baseMapper.selectTablesByFqnPrefix(fqnPrefix);
    }

    @Override
    public List<MetadataEntity> getByDatasourceId(Long datasourceId) {
        return baseMapper.selectByDatasourceId(datasourceId);
    }

    @Override
    public List<MetadataEntity> search(String query, String entityType, Long datasourceId, int page, int size) {
        int offset = (page - 1) * size;
        return baseMapper.fullTextSearch(query, entityType, datasourceId, size, offset);
    }

    @Override
    public List<MetadataEntity> searchScoped(String query, String entityType, Collection<Long> datasourceIds,
                                             int page, int size) {
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            return List.of();
        }
        int offset = (page - 1) * size;
        return baseMapper.fullTextSearchScoped(query, entityType, datasourceIds, size, offset);
    }

    @Override
    public Long getDatasourceIdByEntityId(Long entityId) {
        return entityId == null ? null : baseMapper.selectDatasourceIdByEntityId(entityId);
    }

    @Override
    public MetadataEntity getEntityByIdForUpdate(Long entityId) {
        return entityId == null ? null : baseMapper.selectByIdForUpdate(entityId);
    }

    @Override
    public List<MetadataEntity> getByDatasourceIdsAndType(Collection<Long> datasourceIds, String entityType) {
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            return List.of();
        }
        return baseMapper.selectByDatasourceIdsAndType(datasourceIds, entityType);
    }

    @Override
    public java.util.Set<Long> getEntityIdsByDatasourceIds(java.util.Collection<Long> datasourceIds) {
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(baseMapper.selectEntityIdsByDatasourceIds(datasourceIds));
    }

    @Override
    public MetadataEntity upsert(MetadataEntity entity) {
        // 按 FQN 去重
        MetadataEntity existing = baseMapper.selectByFqn(entity.getFqn());
        if (existing != null) {
            // 更新已有实体
            existing.setDisplayName(entity.getDisplayName());
            existing.setDescription(entity.getDescription());
            existing.setEntityMetadata(entity.getEntityMetadata());
            existing.setOwnerId(entity.getOwnerId());
            existing.setVersion(existing.getVersion() + 1);
            baseMapper.updateById(existing);
            log.debug("实体已更新 fqn={} version={}", entity.getFqn(), existing.getVersion());
            return existing;
        }
        // 创建新实体
        if (entity.getEntityUuid() == null) {
            entity.setEntityUuid(UUID.randomUUID().toString());
        }
        if (entity.getVersion() == null) {
            entity.setVersion(1);
        }
        baseMapper.insert(entity);
        log.debug("实体已创建 fqn={} type={}", entity.getFqn(), entity.getEntityType());
        return entity;
    }
}
