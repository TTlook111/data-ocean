package com.dataocean.module.datasource.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源实体类
 * <p>
 * 对应数据库表 datasource，记录用户接入的外部数据库连接信息，
 * 包括主机地址、端口、数据库名、字符集、启用状态和健康状态等。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("datasource")
public class Datasource {

    /** 状态：禁用 */
    public static final int STATUS_DISABLED = 0;
    /** 状态：启用 */
    public static final int STATUS_ENABLED = 1;

    /** 数据库类型：MySQL */
    public static final String DB_TYPE_MYSQL = "MYSQL";
    /** 健康状态：未知（初始或未检测） */
    public static final String HEALTH_UNKNOWN = "UNKNOWN";
    /** 健康状态：健康 */
    public static final String HEALTH_HEALTHY = "HEALTHY";
    /** 健康状态：不健康 */
    public static final String HEALTH_UNHEALTHY = "UNHEALTHY";

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
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
    /** 字符集（默认 utf8mb4） */
    private String charset;
    /** 启用状态：0-禁用，1-启用 */
    private Integer status;
    /** 健康状态：UNKNOWN / HEALTHY / UNHEALTHY */
    private String healthStatus;
    /** 创建者用户 ID */
    private Long creatorId;
    /** 逻辑删除标记：0-未删除，非0-已删除（存储被删除记录的 ID） */
    private Long deleted;
    /** 创建时间，自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    /** 更新时间，自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
