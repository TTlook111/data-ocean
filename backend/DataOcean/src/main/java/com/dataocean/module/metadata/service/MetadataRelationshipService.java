package com.dataocean.module.metadata.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dataocean.module.metadata.entity.MetadataRelationship;

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
     * 创建关系（按 source + target + type 去重）
     */
    MetadataRelationship upsert(MetadataRelationship relationship);

    /**
     * 删除指定数据源的所有关系（快照发布时清理旧数据）
     */
    void deleteBySourceDatasource(Long datasourceId);
}
