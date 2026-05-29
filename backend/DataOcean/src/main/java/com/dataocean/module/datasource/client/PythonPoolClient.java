package com.dataocean.module.datasource.client;

import java.util.Map;

/**
 * Python AI 服务连接池管理客户端接口。
 * <p>
 * 用于 Java 网关层通知 Python 服务执行连接池相关操作，
 * 包括查看连接池状态、重置和销毁连接池。
 * </p>
 */
public interface PythonPoolClient {

    /**
     * 获取 Python 侧 SQL 连接池仪表盘数据
     *
     * @return 包含 activePools 和 pools 列表的 Map
     */
    Map<String, Object> getPoolDashboard();

    /**
     * 重置指定数据源的连接池
     *
     * @param datasourceId 数据源 ID
     */
    void resetPool(Long datasourceId);

    /**
     * 销毁指定数据源的连接池（密码变更/禁用/删除时调用）
     *
     * @param datasourceId 数据源 ID
     */
    void destroyPool(Long datasourceId);
}
