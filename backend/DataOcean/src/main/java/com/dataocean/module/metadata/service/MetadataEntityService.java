package com.dataocean.module.metadata.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dataocean.module.metadata.entity.MetadataEntity;

import java.util.List;

/**
 * 统一实体服务接口
 *
 * @author dataocean
 */
public interface MetadataEntityService extends IService<MetadataEntity> {

    /**
     * 按 FQN 精确查询实体
     */
    MetadataEntity getByFqn(String fqn);

    /**
     * 按数据源 ID 查询所有 TABLE 类型实体
     */
    List<MetadataEntity> getTablesByDatasource(Long datasourceId, String datasourceName, String databaseName);

    /**
     * 按数据源 ID 查询所有实体
     */
    List<MetadataEntity> getByDatasourceId(Long datasourceId);

    /**
     * 全文搜索实体。
     *
     * @param query        搜索关键词
     * @param entityType   实体类型过滤（可为 null）
     * @param datasourceId 数据源过滤（可为 null，表示全局搜索）
     * @param page         页码
     * @param size         每页大小
     * @return 匹配的实体列表
     */
    List<MetadataEntity> search(String query, String entityType, Long datasourceId, int page, int size);

    /**
     * 创建或更新实体（按 FQN 去重）
     */
    MetadataEntity upsert(MetadataEntity entity);
}
