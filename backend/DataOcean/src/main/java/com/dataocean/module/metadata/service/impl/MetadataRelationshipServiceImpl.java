package com.dataocean.module.metadata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dataocean.module.metadata.entity.po.MetadataEntity;
import com.dataocean.module.metadata.entity.po.MetadataRelationship;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataRelationshipMapper;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import com.dataocean.module.metadata.service.SchemaSnapshotService;
import com.dataocean.module.metadata.support.RelationshipBindingStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final SchemaSnapshotService snapshotService;
    private final ObjectMapper objectMapper;

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
        MetadataEntity entity = entityId == null ? null : entityService.getById(entityId);
        if (entity == null) {
            return List.of();
        }
        return baseMapper.selectLineageByEntityKey(entityId, entity.getEntityType());
    }

    /**
     * 血缘只保留**两端实体都属于可见数据源**的边。
     *
     * <p>血缘是跨实体的图：只校验起点的话，关系另一侧可能属于调用者无负责权限的数据源，
     * 于是接口会把它连带的元数据一起返回。这里对源端与目标端都做可见性判定，
     * 并按端点“类型 + ID”避免数字 ID 重合。</p>
     */
    @Override
    public List<MetadataRelationship> getLineage(Long entityId, Collection<Long> visibleDatasourceIds) {
        MetadataEntity entity = entityId == null ? null : entityService.getById(entityId);
        if (entity == null) {
            return List.of();
        }
        return getLineage(entity.getEntityType(), entityId, visibleDatasourceIds);
    }

    @Override
    public List<MetadataRelationship> getLineage(String entityType, Long entityId,
                                                 Collection<Long> visibleDatasourceIds) {
        if (entityType == null || entityId == null) {
            return List.of();
        }
        // null 表示“由调用方已完成端点裁剪”，不是“可见范围为空”。
        Set<String> visible = visibleDatasourceIds == null
                ? null : entityService.getEntityKeysByDatasourceIds(visibleDatasourceIds);
        return baseMapper.selectLineageByEntityKey(entityId, entityType).stream()
                .filter(rel -> visible == null
                        || (visible.contains(endpointKey(rel.getSourceType(), rel.getSourceId()))
                        && visible.contains(endpointKey(rel.getTargetType(), rel.getTargetId()))))
                .toList();
    }

    /** 实体详情用：只返回两端都可见的关系。 */
    @Override
    public List<MetadataRelationship> getVisibleRelations(Long entityId, String entityType,
                                                          Collection<Long> visibleDatasourceIds) {
        return getVisibleRelationsByType(entityType, entityId, visibleDatasourceIds);
    }

    @Override
    public List<MetadataRelationship> getVisibleRelationsByType(String entityType, Long entityId,
                                                                Collection<Long> visibleDatasourceIds) {
        if (entityType == null || entityId == null) {
            return List.of();
        }
        Set<String> visible = visibleDatasourceIds == null
                ? null : entityService.getEntityKeysByDatasourceIds(visibleDatasourceIds);
        List<MetadataRelationship> result = new ArrayList<>();
        for (MetadataRelationship rel : baseMapper.selectBySource(entityId, entityType)) {
            if (visible == null || (visible.contains(endpointKey(rel.getSourceType(), rel.getSourceId()))
                    && visible.contains(endpointKey(rel.getTargetType(), rel.getTargetId())))) {
                result.add(rel);
            }
        }
        for (MetadataRelationship rel : baseMapper.selectByTarget(entityId, entityType)) {
            if (visible == null || (visible.contains(endpointKey(rel.getSourceType(), rel.getSourceId()))
                    && visible.contains(endpointKey(rel.getTargetType(), rel.getTargetId())))) {
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
        MetadataEntity entity = entityId == null ? null : entityService.getById(entityId);
        if (entity == null) {
            return List.of();
        }
        return getDownstream(entity.getEntityType(), entityId, maxDepth, visibleDatasourceIds);
    }

    @Override
    public List<MetadataRelationship> getDownstream(String entityType, Long entityId, int maxDepth,
                                                    Collection<Long> visibleDatasourceIds) {
        if (entityType == null || entityId == null) {
            return List.of();
        }
        // null 表示调用方已完成端点裁剪；非空时在入队前裁剪无权节点。
        Set<String> visible = visibleDatasourceIds == null
                ? null : entityService.getEntityKeysByDatasourceIds(visibleDatasourceIds);
        List<MetadataRelationship> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        List<EntityNode> currentLevel = List.of(new EntityNode(entityType, entityId));

        for (int depth = 0; depth < maxDepth && !currentLevel.isEmpty(); depth++) {
            List<EntityNode> nextLevel = new ArrayList<>();
            for (EntityNode node : currentLevel) {
                String nodeKey = endpointKey(node.type(), node.id());
                if (nodeKey == null || !visited.add(nodeKey)) {
                    continue;
                }
                for (MetadataRelationship relationship : downstreamRelations(node)) {
                    // UNBOUND 关系保留历史展示，但不能作为有效下游遍历的跳板。
                    if (RelationshipBindingStatus.isInactive(relationship)) {
                        continue;
                    }
                    String targetKey = endpointKey(relationship.getTargetType(), relationship.getTargetId());
                    // 目标节点不可见时不返回该边，也不再沿它继续遍历。
                    if (targetKey == null || (visible != null && !visible.contains(targetKey))) {
                        continue;
                    }
                    result.add(relationship);
                    String nextKey = endpointKey(relationship.getTargetType(), relationship.getTargetId());
                    if (nextKey != null && !visited.contains(nextKey)) {
                        nextLevel.add(new EntityNode(relationship.getTargetType(), relationship.getTargetId()));
                    }
                }
            }
            currentLevel = nextLevel;
        }
        return result;
    }

    /** 下游关系按端点类型选择真实关系：表级 LINEAGE，字段级 DERIVED_FROM。 */
    private List<MetadataRelationship> downstreamRelations(EntityNode node) {
        if (MetadataEntity.TYPE_TABLE.equals(node.type())) {
            return baseMapper.selectBySourceAndRelationType(node.id(), node.type(),
                    MetadataRelationship.TYPE_LINEAGE);
        }
        if (MetadataEntity.TYPE_COLUMN.equals(node.type())) {
            return baseMapper.selectBySourceAndRelationType(node.id(), node.type(),
                    MetadataRelationship.TYPE_DERIVED_FROM);
        }
        return List.of();
    }

    private record EntityNode(String type, Long id) {
    }

    @Override
    public MetadataRelationship upsert(MetadataRelationship relationship) {
        // 按“源类型 + 源 ID + 目标类型 + 目标 ID + 关系类型”去重。
        MetadataRelationship existing = null;
        if (relationship.getSourceType() != null && relationship.getTargetType() != null) {
            existing = baseMapper.selectBetweenTyped(relationship.getSourceId(), relationship.getSourceType(),
                    relationship.getTargetId(), relationship.getTargetType(), relationship.getRelationType());
        } else {
            // 历史调用点未填端点类型时保留原有行为；新代码必须显式设置类型。
            existing = baseMapper.selectBetween(
                    relationship.getSourceId(), relationship.getTargetId(), relationship.getRelationType());
        }
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
        List<MetadataEntity> entities = entityService.getByDatasourceId(datasourceId);
        if (entities.isEmpty()) return;

        List<Long> entityIds = entities.stream().map(MetadataEntity::getId).filter(Objects::nonNull).toList();
        if (entityIds.isEmpty()) return;
        Set<String> entityKeys = new HashSet<>();
        entities.forEach(entity -> {
            String key = endpointKey(entity.getEntityType(), entity.getId());
            if (key != null) entityKeys.add(key);
        });

        // 已确认血缘和人工维护的业务关联不能随结构重建删除。
        List<MetadataRelationship> related = baseMapper.selectList(new LambdaQueryWrapper<MetadataRelationship>()
                .and(wrapper -> wrapper.in(MetadataRelationship::getSourceId, entityIds)
                        .or().in(MetadataRelationship::getTargetId, entityIds)));
        for (MetadataRelationship relationship : related) {
            boolean touchesEntity = entityKeys.contains(endpointKey(
                    relationship.getSourceType(), relationship.getSourceId()))
                    || entityKeys.contains(endpointKey(
                    relationship.getTargetType(), relationship.getTargetId()));
            if (!touchesEntity || RelationshipBindingStatus.isHumanMaintained(relationship)) {
                continue;
            }
            baseMapper.deleteById(relationship.getId());
        }

        log.info("已清理数据源 {} 的结构关系", datasourceId);
    }

    @Override
    public void cleanupStaleSnapshotEntities(Long datasourceId, Collection<String> retainedFqns, Long snapshotId) {
        List<MetadataEntity> entities = entityService.getByDatasourceId(datasourceId);
        if (entities == null || entities.isEmpty()) {
            return;
        }
        Set<String> retained = new HashSet<>();
        if (retainedFqns != null) {
            retainedFqns.stream().filter(Objects::nonNull).map(String::toLowerCase).forEach(retained::add);
        }

        List<MetadataEntity> staleEntities = entities.stream()
                .filter(entity -> entity.getFqn() == null
                        || !retained.contains(entity.getFqn().toLowerCase()))
                .toList();
        if (staleEntities.isEmpty()) {
            return;
        }

        Map<Long, MetadataEntity> staleById = new LinkedHashMap<>();
        Set<String> staleKeys = new HashSet<>();
        for (MetadataEntity entity : staleEntities) {
            if (entity.getId() == null) continue;
            staleById.put(entity.getId(), entity);
            String key = endpointKey(entity.getEntityType(), entity.getId());
            if (key != null) staleKeys.add(key);
        }
        if (staleById.isEmpty()) {
            return;
        }

        List<MetadataRelationship> related = baseMapper.selectList(new LambdaQueryWrapper<MetadataRelationship>()
                .and(wrapper -> wrapper.in(MetadataRelationship::getSourceId, staleById.keySet())
                        .or().in(MetadataRelationship::getTargetId, staleById.keySet())));
        for (MetadataRelationship relationship : related) {
            boolean sourceStale = staleKeys.contains(endpointKey(
                    relationship.getSourceType(), relationship.getSourceId()));
            boolean targetStale = staleKeys.contains(endpointKey(
                    relationship.getTargetType(), relationship.getTargetId()));
            if (!sourceStale && !targetStale) {
                // 数字 ID 相同但端点类型不同，不是这条实体的关系。
                continue;
            }
            if (RelationshipBindingStatus.isHumanMaintained(relationship)) {
                markUnbound(relationship, snapshotId, sourceStale, targetStale, staleById);
                continue;
            }
            baseMapper.deleteById(relationship.getId());
        }

        entityService.removeByIds(staleById.keySet());
        log.info("快照发布清理失效实体 datasourceId={} snapshotId={} stale={}",
                datasourceId, snapshotId, staleById.size());
    }

    /** 把人工关联标记为 UNBOUND，保留关系行与历史，不用“删除后消失”伪装成未关联。 */
    private void markUnbound(MetadataRelationship relationship, Long snapshotId,
                             boolean sourceStale, boolean targetStale,
                             Map<Long, MetadataEntity> staleById) {
        Map<String, Object> metadata = parseMetadata(relationship.getRelationMetadata());
        boolean wasUnbound = "UNBOUND".equalsIgnoreCase(
                stringValue(metadata.get(RelationshipBindingStatus.KEY_STATUS)));
        // 失效时清除端点快照声明，避免单侧快照号继续看起来像“两端已核验”。
        boolean cleared = metadata.remove("bound_snapshot_id") != null;
        cleared |= metadata.remove(RelationshipBindingStatus.KEY_SOURCE_BOUND_SNAPSHOT_ID) != null;
        cleared |= metadata.remove(RelationshipBindingStatus.KEY_TARGET_BOUND_SNAPSHOT_ID) != null;
        if (wasUnbound) {
            if (cleared) {
                relationship.setRelationMetadata(writeMetadata(metadata));
                baseMapper.updateById(relationship);
            }
            return;
        }
        metadata.put(RelationshipBindingStatus.KEY_STATUS, RelationshipBindingStatus.UNBOUND);
        metadata.put(RelationshipBindingStatus.KEY_REASON, "ENDPOINT_REMOVED_FROM_PUBLISHED_SNAPSHOT");
        metadata.put(RelationshipBindingStatus.KEY_INVALIDATED_SNAPSHOT, snapshotId);
        metadata.put(RelationshipBindingStatus.KEY_INVALIDATED_AT, LocalDateTime.now().toString());
        if (sourceStale) {
            MetadataEntity stale = staleById.get(relationship.getSourceId());
            if (stale != null) {
                metadata.putIfAbsent(RelationshipBindingStatus.KEY_PREVIOUS_SOURCE_FQN, stale.getFqn());
                Long datasourceId = datasourceIdOf(stale);
                if (datasourceId != null) {
                    // 保留旧归属用于权限判定；失效不能让术语退化成“全局未绑定”而扩大可见范围。
                    metadata.putIfAbsent(RelationshipBindingStatus.KEY_PREVIOUS_SOURCE_DATASOURCE_ID,
                            datasourceId);
                }
            }
        }
        if (targetStale) {
            MetadataEntity stale = staleById.get(relationship.getTargetId());
            if (stale != null) {
                metadata.putIfAbsent(RelationshipBindingStatus.KEY_PREVIOUS_TARGET_FQN, stale.getFqn());
                Long datasourceId = datasourceIdOf(stale);
                if (datasourceId != null) {
                    metadata.putIfAbsent(RelationshipBindingStatus.KEY_PREVIOUS_TARGET_DATASOURCE_ID,
                            datasourceId);
                }
            }
        }
        relationship.setRelationMetadata(writeMetadata(metadata));
        baseMapper.updateById(relationship);
    }

    private Long datasourceIdOf(MetadataEntity entity) {
        if (entity == null) return null;
        Object value = parseMetadata(entity.getEntityMetadata()).get("datasource_id");
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @Override
    public void deleteRelationsForEntityKeys(Collection<String> entityKeys, Collection<String> relationTypes) {
        if (entityKeys == null || entityKeys.isEmpty() || relationTypes == null || relationTypes.isEmpty()) {
            return;
        }
        Set<String> keys = new HashSet<>(entityKeys);
        Set<Long> ids = new HashSet<>();
        for (String key : keys) {
            int separator = key == null ? -1 : key.lastIndexOf('#');
            if (separator <= 0) continue;
            try {
                ids.add(Long.parseLong(key.substring(separator + 1)));
            } catch (NumberFormatException ignored) {
                // 端点身份格式非法时跳过，不影响其它已解析端点。
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Set<String> types = new HashSet<>(relationTypes);
        List<MetadataRelationship> related = baseMapper.selectList(new LambdaQueryWrapper<MetadataRelationship>()
                .in(MetadataRelationship::getRelationType, types)
                .and(wrapper -> wrapper.in(MetadataRelationship::getSourceId, ids)
                        .or().in(MetadataRelationship::getTargetId, ids)));
        for (MetadataRelationship relationship : related) {
            boolean touches = keys.contains(endpointKey(
                    relationship.getSourceType(), relationship.getSourceId()))
                    || keys.contains(endpointKey(
                    relationship.getTargetType(), relationship.getTargetId()));
            if (touches) {
                baseMapper.deleteById(relationship.getId());
            }
        }
    }

    @Override
    public void rebindConfirmedLineageForSnapshot(Long datasourceId, Long snapshotId) {
        MetadataSnapshot currentPublished = snapshotService.getPublishedSnapshot(datasourceId);
        if (currentPublished == null || !Objects.equals(currentPublished.getId(), snapshotId)) {
            // 并发发布期间旧同步不能把血缘重新绑定到已经过期的快照。
            log.info("快照已不是当前发布版本，跳过血缘重绑定 datasourceId={} snapshotId={}",
                    datasourceId, snapshotId);
            return;
        }
        List<MetadataEntity> currentEntities = entityService.getByDatasourceId(datasourceId);
        Map<String, MetadataEntity> byFqn = currentEntities.stream()
                .filter(entity -> entity.getFqn() != null && entity.getId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        entity -> entity.getFqn().toLowerCase(), entity -> entity, (left, right) -> left));
        String datasourceFqnPrefix = currentEntities.stream()
                .filter(entity -> MetadataEntity.TYPE_DATASOURCE.equals(entity.getEntityType()))
                .map(MetadataEntity::getFqn)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseGet(() -> currentEntities.stream()
                        .filter(entity -> MetadataEntity.TYPE_TABLE.equals(entity.getEntityType()))
                        .map(MetadataEntity::getFqn)
                        .filter(java.util.Objects::nonNull)
                        .map(fqn -> fqn.indexOf('.') > 0 ? fqn.substring(0, fqn.indexOf('.')) : fqn)
                        .findFirst().orElse("")).toLowerCase();
        List<MetadataRelationship> candidates = baseMapper.selectList(
                new LambdaQueryWrapper<MetadataRelationship>()
                        .in(MetadataRelationship::getRelationType,
                                MetadataRelationship.TYPE_LINEAGE,
                                MetadataRelationship.TYPE_DERIVED_FROM));
        Set<Long> currentEntityIds = currentEntities.stream()
                .map(MetadataEntity::getId).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, MetadataSnapshot> publishedSnapshots = new LinkedHashMap<>();
        int rebound = 0;
        int unbound = 0;
        for (MetadataRelationship relationship : candidates) {
            if (!RelationshipBindingStatus.isConfirmedLineage(relationship)) continue;
            Map<String, Object> metadata = parseMetadata(relationship.getRelationMetadata());
            String sourceFqn = stringValue(metadata.get("source_fqn"));
            String targetFqn = stringValue(metadata.get("target_fqn"));

            // 没有稳定 FQN 时无法按 FQN 重绑定；只处理确实触及本源的关系，并明确标记不可核验。
            if (sourceFqn == null || targetFqn == null) {
                if (touchesEntity(relationship, currentEntityIds)) {
                    markUnbound(relationship, metadata, snapshotId,
                            sourceFqn == null ? "SOURCE_ENDPOINT_FQN_MISSING" : null,
                            targetFqn == null ? "TARGET_ENDPOINT_FQN_MISSING" : null,
                            sourceFqn, targetFqn);
                    unbound++;
                }
                continue;
            }

            boolean sourceInScope = sourceFqn.toLowerCase().startsWith(datasourceFqnPrefix + ".");
            boolean targetInScope = targetFqn.toLowerCase().startsWith(datasourceFqnPrefix + ".");
            if (!sourceInScope && !targetInScope) {
                continue;
            }

            String fallbackType = MetadataRelationship.TYPE_LINEAGE.equals(relationship.getRelationType())
                    ? MetadataEntity.TYPE_TABLE : MetadataEntity.TYPE_COLUMN;
            String sourceExpectedType = relationship.getSourceType() == null
                    ? fallbackType : relationship.getSourceType();
            String targetExpectedType = relationship.getTargetType() == null
                    ? fallbackType : relationship.getTargetType();

            EndpointCheck sourceCheck = verifyEndpoint("SOURCE", relationship.getSourceId(),
                    sourceExpectedType, sourceFqn, sourceInScope, byFqn, datasourceId, snapshotId,
                    publishedSnapshots);
            EndpointCheck targetCheck = verifyEndpoint("TARGET", relationship.getTargetId(),
                    targetExpectedType, targetFqn, targetInScope, byFqn, datasourceId, snapshotId,
                    publishedSnapshots);

            if (!sourceCheck.valid() || !targetCheck.valid()) {
                // 两端必须同时有效；一端未解决时保持 UNBOUND，不能因为本源同步成功就恢复整条关系。
                markUnbound(relationship, metadata, snapshotId,
                        sourceCheck.failure(), targetCheck.failure(), sourceFqn, targetFqn);
                unbound++;
                continue;
            }

            // 只改写属于本源一侧的端点；外源端点保持其自身同步结果，不擅自替换。
            if (sourceInScope) {
                relationship.setSourceId(sourceCheck.entity().getId());
                relationship.setSourceType(sourceCheck.entity().getEntityType());
            }
            if (targetInScope) {
                relationship.setTargetId(targetCheck.entity().getId());
                relationship.setTargetType(targetCheck.entity().getEntityType());
            }
            metadata.put(RelationshipBindingStatus.KEY_STATUS, RelationshipBindingStatus.BOUND);
            metadata.remove(RelationshipBindingStatus.KEY_REASON);
            metadata.remove(RelationshipBindingStatus.KEY_UNVERIFIED_ENDPOINTS);
            metadata.remove(RelationshipBindingStatus.KEY_INVALIDATED_SNAPSHOT);
            metadata.remove(RelationshipBindingStatus.KEY_INVALIDATED_AT);
            metadata.put(RelationshipBindingStatus.KEY_SOURCE_BOUND_SNAPSHOT_ID,
                    sourceCheck.boundSnapshotId());
            metadata.put(RelationshipBindingStatus.KEY_TARGET_BOUND_SNAPSHOT_ID,
                    targetCheck.boundSnapshotId());
            if (Objects.equals(sourceCheck.boundSnapshotId(), targetCheck.boundSnapshotId())) {
                // 同源关系可以用单个快照号表达；跨源关系不使用该字段，避免一侧代表两端。
                metadata.put("bound_snapshot_id", sourceCheck.boundSnapshotId());
            } else {
                metadata.remove("bound_snapshot_id");
            }
            metadata.putIfAbsent("confirmation_status", "CONFIRMED");
            relationship.setRelationMetadata(writeMetadata(metadata));
            baseMapper.updateById(relationship);
            rebound++;
        }
        log.info("确认血缘快照重绑定完成 datasourceId={} snapshotId={} rebound={} unbound={}",
                datasourceId, snapshotId, rebound, unbound);
    }

    /**
     * 核验血缘端点。
     *
     * <p>本源端点必须匹配本次发布的实体；外源端点必须仍存在、类型/FQN 一致，
     * 且其记录的快照就是该数据源当前有效的已发布快照。</p>
     */
    private EndpointCheck verifyEndpoint(String role, Long endpointId, String expectedType,
                                         String fqn, boolean inScope,
                                         Map<String, MetadataEntity> byFqn,
                                         Long datasourceId, Long snapshotId,
                                         Map<Long, MetadataSnapshot> publishedSnapshots) {
        MetadataEntity entity;
        Long boundSnapshotId;
        if (inScope) {
            entity = byFqn.get(fqn.toLowerCase());
            if (entity == null) {
                return EndpointCheck.failed(role + "_ENDPOINT_NOT_IN_PUBLISHED_SNAPSHOT");
            }
            Long entityDatasourceId = datasourceIdOf(entity);
            if (!Objects.equals(entityDatasourceId, datasourceId)) {
                return EndpointCheck.failed(role + "_ENDPOINT_DATASOURCE_MISMATCH");
            }
            Long entitySnapshotId = snapshotIdOf(entity);
            if (!Objects.equals(entitySnapshotId, snapshotId)) {
                return EndpointCheck.failed(role + "_ENDPOINT_SNAPSHOT_MISMATCH");
            }
            boundSnapshotId = snapshotId;
        } else {
            entity = endpointId == null ? null : entityService.getById(endpointId);
            if (entity == null) {
                return EndpointCheck.failed(role + "_ENDPOINT_NOT_FOUND");
            }
            Long entityDatasourceId = datasourceIdOf(entity);
            if (entityDatasourceId == null) {
                return EndpointCheck.failed(role + "_ENDPOINT_DATASOURCE_UNKNOWN");
            }
            Long entitySnapshotId = snapshotIdOf(entity);
            if (entitySnapshotId == null) {
                return EndpointCheck.failed(role + "_ENDPOINT_SNAPSHOT_UNKNOWN");
            }
            MetadataSnapshot published = publishedSnapshot(entityDatasourceId, publishedSnapshots);
            if (published == null || !Objects.equals(published.getId(), entitySnapshotId)) {
                return EndpointCheck.failed(role + "_ENDPOINT_NOT_IN_CURRENT_PUBLISHED_SNAPSHOT");
            }
            boundSnapshotId = entitySnapshotId;
        }
        if (!expectedType.equals(entity.getEntityType())) {
            return EndpointCheck.failed(role + "_ENDPOINT_TYPE_MISMATCH");
        }
        if (entity.getFqn() == null || !entity.getFqn().equalsIgnoreCase(fqn)) {
            return EndpointCheck.failed(role + "_ENDPOINT_FQN_MISMATCH");
        }
        return new EndpointCheck(entity, boundSnapshotId, null);
    }

    private MetadataSnapshot publishedSnapshot(Long datasourceId, Map<Long, MetadataSnapshot> cache) {
        if (datasourceId == null) return null;
        if (cache.containsKey(datasourceId)) return cache.get(datasourceId);
        MetadataSnapshot snapshot = snapshotService.getPublishedSnapshot(datasourceId);
        cache.put(datasourceId, snapshot);
        return snapshot;
    }

    private void markUnbound(MetadataRelationship relationship, Map<String, Object> metadata,
                             Long snapshotId, String sourceFailure, String targetFailure,
                             String sourceFqn, String targetFqn) {
        List<String> unverified = new ArrayList<>();
        if (sourceFailure != null) unverified.add("SOURCE");
        if (targetFailure != null) unverified.add("TARGET");
        String reason = String.join("; ",
                java.util.stream.Stream.of(sourceFailure, targetFailure)
                        .filter(Objects::nonNull).toList());
        metadata.put(RelationshipBindingStatus.KEY_STATUS, RelationshipBindingStatus.UNBOUND);
        metadata.put(RelationshipBindingStatus.KEY_REASON,
                reason.isBlank() ? "ENDPOINT_NOT_VERIFIED" : reason);
        metadata.put(RelationshipBindingStatus.KEY_UNVERIFIED_ENDPOINTS, unverified);
        // 首次失效的快照/时间保留，避免另一端后续重新核验时改写历史失效点。
        metadata.putIfAbsent(RelationshipBindingStatus.KEY_INVALIDATED_SNAPSHOT, snapshotId);
        metadata.putIfAbsent(RelationshipBindingStatus.KEY_INVALIDATED_AT, LocalDateTime.now().toString());
        // 单侧快照号不能表达“两端均已核验”；失效时清除。
        metadata.remove("bound_snapshot_id");
        metadata.remove(RelationshipBindingStatus.KEY_SOURCE_BOUND_SNAPSHOT_ID);
        metadata.remove(RelationshipBindingStatus.KEY_TARGET_BOUND_SNAPSHOT_ID);
        if (sourceFqn != null) {
            metadata.putIfAbsent(RelationshipBindingStatus.KEY_PREVIOUS_SOURCE_FQN, sourceFqn);
        }
        if (targetFqn != null) {
            metadata.putIfAbsent(RelationshipBindingStatus.KEY_PREVIOUS_TARGET_FQN, targetFqn);
        }
        relationship.setRelationMetadata(writeMetadata(metadata));
        baseMapper.updateById(relationship);
    }

    private boolean touchesEntity(MetadataRelationship relationship, Set<Long> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) return false;
        return (isLineageEntityType(relationship.getSourceType())
                && relationship.getSourceId() != null && entityIds.contains(relationship.getSourceId()))
                || (isLineageEntityType(relationship.getTargetType())
                && relationship.getTargetId() != null && entityIds.contains(relationship.getTargetId()));
    }

    private boolean isLineageEntityType(String type) {
        return type == null || MetadataEntity.TYPE_TABLE.equals(type)
                || MetadataEntity.TYPE_COLUMN.equals(type);
    }

    private Long snapshotIdOf(MetadataEntity entity) {
        if (entity == null) return null;
        Object value = parseMetadata(entity.getEntityMetadata()).get("snapshot_id");
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private record EndpointCheck(MetadataEntity entity, Long boundSnapshotId, String failure) {
        static EndpointCheck failed(String failure) {
            return new EndpointCheck(null, null, failure);
        }

        boolean valid() {
            return entity != null && boundSnapshotId != null && failure == null;
        }
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return new java.util.LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new java.util.LinkedHashMap<>();
        }
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            throw new IllegalStateException("无法持久化血缘绑定状态", e);
        }
    }

    private String stringValue(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private String endpointKey(String entityType, Long id) {
        return MetadataEntity.endpointKey(entityType, id);
    }
}
