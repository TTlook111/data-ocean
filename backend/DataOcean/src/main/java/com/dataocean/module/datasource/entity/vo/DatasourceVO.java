package com.dataocean.module.datasource.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源详情视图对象
 * <p>
 * 用于管理端数据源列表和详情展示，包含数据源基本信息、
 * 健康状态、创建者名称和最近一次健康检查结果。
 * </p>
 *
 * @author dataocean
 */
@Data
public class DatasourceVO {

    /** 数据源 ID */
    private Long id;
    /** 数据源名称 */
    private String name;
    /** 数据源描述 */
    private String description;
    /** 数据库类型（如 MYSQL） */
    private String dbType;
    /** 数据库主机地址 */
    private String host;
    /** 数据库端口号 */
    private Integer port;
    /** 数据库名称 */
    private String databaseName;
    /** 字符集 */
    private String charset;
    /** 启用状态：0-禁用，1-启用 */
    private Integer status;
    /** 健康状态：UNKNOWN / HEALTHY / UNHEALTHY */
    private String healthStatus;
    /** 连接用户名 */
    private String username;
    /** 创建者姓名 */
    private String creatorName;
    /** 最近一次健康检查是否成功 */
    private Boolean lastCheckSuccess;
    /** 最近一次健康检查时间 */
    private LocalDateTime lastCheckTime;
    /** 创建时间 */
    private LocalDateTime createdAt;
}
