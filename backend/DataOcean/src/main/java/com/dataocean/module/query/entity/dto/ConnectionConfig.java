package com.dataocean.module.query.entity.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 数据源连接配置 DTO
 * <p>
 * 用于传递数据源连接信息给 Python 服务。
 * </p>
 *
 * @author dataocean
 */
@Data
@Builder
public class ConnectionConfig {

    /** 数据库主机 */
    private String host;

    /** 数据库端口 */
    private Integer port;

    /** 数据库名称 */
    private String database;

    /** 用户名 */
    private String username;

    /** 密码（明文，Java 侧解密后传入） */
    private String password;
}
