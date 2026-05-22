package com.dataocean.module.datasource.service;

import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.vo.DatasourceConnectionTestVO;

/**
 * 数据源连接测试服务接口
 * <p>
 * 提供对数据源的实际 JDBC 连接测试能力，支持新建数据源时的参数测试
 * 和已保存数据源的连通性验证，并可选地记录健康检查结果。
 * </p>
 *
 * @author dataocean
 */
public interface DatasourceConnectionService {

    /**
     * 使用指定参数测试数据库连接（不记录健康检查）
     * <p>
     * 适用于新建数据源前的连接验证场景。
     * </p>
     *
     * @param host         数据库主机地址
     * @param port         数据库端口号
     * @param databaseName 数据库名称
     * @param charset      字符集
     * @param username     连接用户名
     * @param password     连接密码（明文）
     * @return 连接测试结果
     */
    DatasourceConnectionTestVO testConnection(String host,
                                                  Integer port,
                                                  String databaseName,
                                                  String charset,
                                                  String username,
                                                  String password);

    /**
     * 对已保存的数据源执行连接测试并记录健康检查
     * <p>
     * 适用于已有数据源的定时或手动健康检查场景。
     * </p>
     *
     * @param datasource 数据源实体
     * @param username   连接用户名
     * @param password   连接密码（明文）
     * @param checkType  检查类型：MANUAL-手动 / SCHEDULED-定时
     * @return 连接测试结果
     */
    DatasourceConnectionTestVO testConnection(Datasource datasource, String username, String password, String checkType);
}
