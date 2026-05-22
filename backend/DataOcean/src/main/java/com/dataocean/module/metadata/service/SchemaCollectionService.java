package com.dataocean.module.metadata.service;

/**
 * Schema 采集服务接口。
 * <p>
 * 负责编排元数据全量同步流程，包括建立数据库连接、采集表/字段/索引/关系信息、
 * 生成快照、可选采集统计信息等。支持手动触发和定时调度两种方式。
 * </p>
 */
public interface SchemaCollectionService {

    /**
     * 执行手动触发的全量同步。
     *
     * @param datasourceId      目标数据源ID
     * @param includeStatistics 是否同时采集统计信息
     * @return 创建的同步任务ID
     */
    Long executeFullSync(Long datasourceId, boolean includeStatistics);

    /**
     * 执行定时调度触发的全量同步。
     *
     * @param datasourceId      目标数据源ID
     * @param includeStatistics 是否同时采集统计信息
     * @return 创建的同步任务ID
     */
    Long executeScheduledFullSync(Long datasourceId, boolean includeStatistics);
}
