package com.dataocean.module.metadata.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dataocean.module.metadata.entity.po.MetadataRelationship;

import java.util.Collection;
import java.util.List;

/**
 * 统一关系边服务接口
 *
 * @author dataocean
 */
public interface MetadataRelationshipService extends IService<MetadataRelationship> {

    /**
     * 查询指定实体的所有出向关系
     */
    List<MetadataRelationship> getBySource(Long sourceId, String sourceType);

    /**
     * 查询指定实体的所有入向关系
     */
    List<MetadataRelationship> getByTarget(Long targetId, String targetType);

    /**
     * 查询指定实体的血缘关系（上游 + 下游）
     */
    List<MetadataRelationship> getLineage(Long entityId);

    /**
     * 查询指定实体的下游依赖（递归，最大深度 10）
     */
    List<MetadataRelationship> getDownstream(Long entityId, int maxDepth);

    /**
     * 血缘只保留两端实体都可见的边。
     *
     * <p>血缘是跨实体的图，只校验起点会让关系另一侧属于无权数据源的元数据一并返回。</p>
     */
    List<MetadataRelationship> getLineage(Long entityId, Collection<Long> visibleDatasourceIds);

    /**
     * 按“类型 + ID”读取实体血缘，只保留两端都可见的边。
     */
    List<MetadataRelationship> getLineage(String entityType, Long entityId,
                                          Collection<Long> visibleDatasourceIds);

    /** 实体详情用：只返回两端都可见的关系（incoming + outgoing）。 */
    List<MetadataRelationship> getVisibleRelations(Long entityId, String entityType,
                                                   Collection<Long> visibleDatasourceIds);

    /**
     * 按“类型 + ID”返回两端都可见的入向和出向关系。
     */
    List<MetadataRelationship> getVisibleRelationsByType(String entityType, Long entityId,
                                                         Collection<Long> visibleDatasourceIds);

    /**
     * 下游影响分析：只返回两端可见的边，并在遇到无权节点时停止继续遍历。
     */
    List<MetadataRelationship> getDownstream(Long entityId, int maxDepth,
                                             Collection<Long> visibleDatasourceIds);

    /**
     * 按“类型 + ID”遍历下游：
     * 表沿 LINEAGE，字段沿 DERIVED_FROM；无权节点既不返回也不继续遍历。
     */
    List<MetadataRelationship> getDownstream(String entityType, Long entityId, int maxDepth,
                                             Collection<Long> visibleDatasourceIds);

    /**
     * 创建关系（按 source + target + type 去重）
     */
    MetadataRelationship upsert(MetadataRelationship relationship);

    /**
     * 删除指定数据源的所有关系（快照发布时清理旧数据）。
     *
     * <p>保留已确认血缘与人工维护的业务关联；历史调用点仍可使用，
     * 新发布流程使用 {@link #cleanupStaleSnapshotEntities}。</p>
     */
    void deleteBySourceDatasource(Long datasourceId);

    /**
     * 清理新发布快照中已不存在的实体。
     *
     * <p>保留仍然存在的同一逻辑对象（按 FQN 稳定身份），只删除其结构关系；
     * 术语映射与人工标签在端点消失时标记为 UNBOUND 并保留历史，不能无差别删除。</p>
     *
     * @param datasourceId  数据源 ID
     * @param retainedFqns  当前快照保留的实体 FQN（小写比较）
     * @param snapshotId    本次发布快照 ID
     */
    void cleanupStaleSnapshotEntities(Long datasourceId, Collection<String> retainedFqns, Long snapshotId);

    /**
     * 删除这些实体上指定类型的结构关系（如重建 FOREIGN_KEY 前清理旧边）。
     *
     * @param entityKeys    “类型 + ID”端点身份
     * @param relationTypes 需要删除的关系类型
     */
    void deleteRelationsForEntityKeys(Collection<String> entityKeys, Collection<String> relationTypes);

    /**
     * 将已确认的人工作业/ETL 血缘按稳定 FQN 重新绑定到新发布快照中的实体。
     * 如果任一端在新快照缺失，事实保留为 UNBOUND，不得进入问数知识。
     */
    void rebindConfirmedLineageForSnapshot(Long datasourceId, Long snapshotId);
}
