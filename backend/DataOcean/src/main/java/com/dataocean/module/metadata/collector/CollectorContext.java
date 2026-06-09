package com.dataocean.module.metadata.collector;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/**
 * 元数据采集上下文。
 * <p>
 * 封装采集过程中共用的参数，避免在每个 Collector 方法中重复传递。
 * </p>
 *
 * @param connection   数据库连接
 * @param metaData     数据库元数据
 * @param catalog      数据库 catalog
 * @param datasourceId 数据源 ID
 * @param snapshotId   快照 ID
 */
public record CollectorContext(
        Connection connection,
        DatabaseMetaData metaData,
        String catalog,
        Long datasourceId,
        Long snapshotId
) {
    /**
     * 创建 CollectorContext 实例。
     *
     * @param connection   数据库连接
     * @param datasourceId 数据源 ID
     * @param snapshotId   快照 ID
     * @return CollectorContext 实例
     * @throws SQLException 获取数据库元数据失败时抛出
     */
    public static CollectorContext of(Connection connection, Long datasourceId, Long snapshotId) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = connection.getCatalog();
        return new CollectorContext(connection, metaData, catalog, datasourceId, snapshotId);
    }
}
