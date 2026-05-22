package com.dataocean.module.datasource.client;

/**
 * Python AI 服务连接池管理客户端接口
 * <p>
 * 用于 Java 网关层通知 Python 服务执行连接池相关操作，
 * 例如在数据源密码变更或删除时销毁对应的数据库连接池。
 * </p>
 *
 * @author dataocean
 */
public interface PythonPoolClient {

    /**
     * 通知 Python 服务销毁指定数据源的数据库连接池
     * <p>
     * 在数据源密码变更、禁用或删除时调用，确保 Python 端不再使用旧的连接。
     * 调用失败时仅记录警告日志，不影响主流程。
     * </p>
     *
     * @param datasourceId 数据源 ID
     */
    void destroyPool(Long datasourceId);
}
