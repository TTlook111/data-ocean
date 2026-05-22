package com.dataocean.module.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源健康检查记录实体类
 * <p>
 * 对应数据库表 datasource_health_check，记录每次对数据源执行连接健康检查的结果，
 * 包括检查类型（手动/定时）、是否成功、响应时间、数据库版本和错误信息。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("datasource_health_check")
public class DatasourceHealthCheck {

    /** 检查类型：手动触发 */
    public static final String TYPE_MANUAL = "MANUAL";
    /** 检查类型：定时调度 */
    public static final String TYPE_SCHEDULED = "SCHEDULED";

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 数据源 ID */
    private Long datasourceId;
    /** 检查类型：MANUAL-手动 / SCHEDULED-定时 */
    private String checkType;
    /** 是否成功：1-成功，0-失败 */
    private Integer success;
    /** 响应时间（毫秒） */
    private Integer responseTimeMs;
    /** 数据库服务器版本号 */
    private String serverVersion;
    /** 失败时的错误信息 */
    private String errorMessage;
    /** 检查执行时间 */
    private LocalDateTime checkedAt;
}
