package com.dataocean.module.metadata.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dataocean.module.metadata.entity.MetadataEntity;

import java.util.Collection;

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
     * 全文搜索实体，按**负责源范围**收窄。
     *
     * <p>`datasourceIds` 为空表示调用者没有任何负责源，直接返回空列表——
     * 不能退化成全局搜索，也不能生成 `IN ()`。</p>
     */
    List<MetadataEntity> searchScoped(String query, String entityType, Collection<Long> datasourceIds,
                                      int page, int size);

    /** 实体所属的数据源 ID；实体不存在或没有数据源信息时返回 null。 */
    Long getDatasourceIdByEntityId(Long entityId);

    /**
     * 按 ID 做当前读并加行锁；必须事务内调用。
     *
     * <p>用于“拿到其他行锁后需要重新读取实体最新状态”的场景：REPEATABLE READ 下
     * 普通 `getById` 读的是事务开始时的快照。</p>
     */
    MetadataEntity getEntityByIdForUpdate(Long entityId);

    /**
     * 属于这些数据源的全部实体 ID；用于血缘图的节点可见性判定。
     * `datasourceIds` 为空返回空集合。
     */
    java.util.Set<Long> getEntityIdsByDatasourceIds(java.util.Collection<Long> datasourceIds);

    /**
     * 按负责源范围读取指定类型的全部实体；`datasourceIds` 为空返回空列表。
     *
     * <p>用于替代原先“不传数据源就全局扫描”的写法。</p>
     */
    List<MetadataEntity> getByDatasourceIdsAndType(Collection<Long> datasourceIds, String entityType);

    /**
     * 创建或更新实体（按 FQN 去重）
     */
    MetadataEntity upsert(MetadataEntity entity);
}
