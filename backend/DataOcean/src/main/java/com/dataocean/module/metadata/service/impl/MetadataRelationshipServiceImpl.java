package com.dataocean.module.metadata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.mapper.MetadataRelationshipMapper;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 统一关系边服务实现
 *
 * @author dataocean
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataRelationshipServiceImpl extends ServiceImpl<MetadataRelationshipMapper, MetadataRelationship>
        implements MetadataRelationshipService {

    private final MetadataEntityService entityService;

    @Override
    public List<MetadataRelationship> getBySource(Long sourceId, String sourceType) {
        return baseMapper.selectBySource(sourceId, sourceType);
    }

    @Override
    public List<MetadataRelationship> getByTarget(Long targetId, String targetType) {
        return baseMapper.selectByTarget(targetId, targetType);
    }

    @Override
    public List<MetadataRelationship> getLineage(Long entityId) {
        return baseMapper.selectLineageByEntityId(entityId);
    }

    /**
     * 血缘只保留**两端实体都属于可见数据源**的边。
     *
     * <p>血缘是跨实体的图：只校验起点的话，关系另一侧可能属于调用者无负责权限的数据源，
     * 于是接口会把它连带的元数据一起返回。这里对源端与目标端都做可见性判定。</p>
     */
    @Override
    public List<MetadataRelationship> getLineage(Long entityId, Collection<Long> visibleDatasourceIds) {
        Set<Long> visible = entityService.getEntityIdsByDatasourceIds(visibleDatasourceIds);
        return baseMapper.selectLineageByEntityId(entityId).stream()
                .filter(rel -> visible.contains(rel.getSourceId()) && visible.contains(rel.getTargetId()))
                .toList();
    }

    /** 实体详情用：只返回两端都可见的关系。 */
    @Override
    public List<MetadataRelationship> getVisibleRelations(Long entityId, String entityType,
                                                          Collection<Long> visibleDatasourceIds) {
        Set<Long> visible = entityService.getEntityIdsByDatasourceIds(visibleDatasourceIds);
        List<MetadataRelationship> result = new ArrayList<>();
        for (MetadataRelationship rel : baseMapper.selectBySource(entityId, entityType)) {
            if (visible.contains(rel.getSourceId()) && visible.contains(rel.getTargetId())) {
                result.add(rel);
            }
        }
        for (MetadataRelationship rel : baseMapper.selectByTarget(entityId, entityType)) {
            if (visible.contains(rel.getSourceId()) && visible.contains(rel.getTargetId())) {
                result.add(rel);
            }
        }
        return result;
    }

    @Override
    public List<MetadataRelationship> getDownstream(Long entityId, int maxDepth) {
        return getDownstream(entityId, maxDepth, null);
    }

    /**
     * 下游影响分析：只返回两端可见的边，并**在遇到无权节点时停止向下遍历**。
     *
     * <p>仅过滤返回结果是不够的——继续沿无权节点遍历会把更下游的可见节点也带出来，
     * 等于用一条越界的边把无权子图的形状暴露出去。</p>
     */
    @Override
    public List<MetadataRelationship> getDownstream(Long entityId, int maxDepth,
                                                    Collection<Long> visibleDatasourceIds) {
        Set<Long> visible = visibleDatasourceIds == null
                ? null
                : entityService.getEntityIdsByDatasourceIds(visibleDatasourceIds);
        // BFS 遍历下游依赖
        List<MetadataRelationship> result = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        List<Long> currentLevel = List.of(entityId);

        for (int depth = 0; depth < maxDepth && !currentLevel.isEmpty(); depth++) {
            List<Long> nextLevel = new ArrayList<>();
            for (Long id : currentLevel) {
                if (!visited.add(id)) continue;
                // 查找以 id 为 source 的 LINEAGE 关系
                List<MetadataRelationship> downstream = baseMapper.selectBySource(id, null).stream()
                        .filter(r -> MetadataRelationship.TYPE_LINEAGE.equals(r.getRelationType()))
                        .toList();
                for (MetadataRelationship rel : downstream) {
                    if (visible != null && !visible.contains(rel.getTargetId())) {
                        // 目标节点不可见：不返回该边，也不再向它的下游继续。
                        continue;
                    }
                    result.add(rel);
                    if (!visited.contains(rel.getTargetId())) {
                        nextLevel.add(rel.getTargetId());
                    }
                }
            }
            currentLevel = nextLevel;
        }
        return result;
    }

    @Override
    public MetadataRelationship upsert(MetadataRelationship relationship) {
        // 按 source + target + type 去重
        MetadataRelationship existing = baseMapper.selectBetween(
                relationship.getSourceId(), relationship.getTargetId(), relationship.getRelationType());
        if (existing != null) {
            existing.setRelationMetadata(relationship.getRelationMetadata());
            baseMapper.updateById(existing);
            return existing;
        }
        baseMapper.insert(relationship);
        return relationship;
    }

    @Override
    public void deleteBySourceDatasource(Long datasourceId) {
        // 查找该数据源的所有 TABLE 实体
        List<MetadataEntity> entities = entityService.getByDatasourceId(datasourceId);
        if (entities.isEmpty()) return;

        List<Long> entityIds = entities.stream().map(MetadataEntity::getId).toList();

        // 删除这些实体作为 source 的所有关系
        baseMapper.delete(new LambdaQueryWrapper<MetadataRelationship>()
                .in(MetadataRelationship::getSourceId, entityIds));
        // 删除这些实体作为 target 的所有关系
        baseMapper.delete(new LambdaQueryWrapper<MetadataRelationship>()
                .in(MetadataRelationship::getTargetId, entityIds));

        log.info("已清理数据源 {} 的所有关系", datasourceId);
    }
}
