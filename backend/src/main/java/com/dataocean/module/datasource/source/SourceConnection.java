package com.dataocean.module.datasource.source;

import java.util.List;

/**
 * 源库读取连接的固定范围，密码从受控秘密存储单独取得。
 *
 * @param type 业务库驱动类型
 * @param host 部署允许的连接地址或待解析域名
 * @param port 业务库 TCP 端口
 * @param database 连接目标数据库
 * @param schemas PostgreSQL 采集范围，MySQL 保持空集合
 * @param account 专用只读用户名
 * @param queryTimeoutSeconds 单次元数据查询等待上限
 */
public record SourceConnection(
        Type type,
        String host,
        int port,
        String database,
        List<String> schemas,
        String account,
        int queryTimeoutSeconds) {

    /**
     * 正式支持范围按适配器的真实源库验收结果配置。
     */
    public enum Type {
        MYSQL,
        POSTGRESQL
    }
}
