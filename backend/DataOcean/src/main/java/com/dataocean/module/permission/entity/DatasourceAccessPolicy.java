package com.dataocean.module.permission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源行列级访问策略实体类
 * <p>
 * 对应数据库表 datasource_access_policy，定义细粒度的表级、列级、行级访问控制策略。
 * 支持按用户/角色/部门维度配置，可设置允许、禁止或脱敏访问。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("datasource_access_policy")
public class DatasourceAccessPolicy {

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源 ID */
    private Long datasourceId;

    /** 授权主体类型: USER/ROLE/DEPARTMENT */
    private String subjectType;

    /** 授权主体 ID（用户ID/角色ID/部门ID） */
    private Long subjectId;

    /** 表名（* 表示所有表） */
    private String tableName;

    /** 列名（NULL 表示表级策略） */
    private String columnName;

    /** 访问类型: ALLOW/DENY/MASK */
    private String accessType;

    /** 脱敏策略: PHONE/ID_CARD/EMAIL/BANK_CARD/NAME */
    private String maskStrategy;

    /** 行级过滤 SQL 表达式 */
    private String rowFilterExpression;

    /** 优先级（越低越优先：系统级 0-99，管理员 100-199，默认 200+） */
    private Integer priority;

    /** 策略生效开始时间（NULL 表示立即生效） */
    private LocalDateTime validFrom;

    /** 策略生效结束时间（NULL 表示永久有效） */
    private LocalDateTime validUntil;

    /** 时间计划（JSON：weekdays + hours） */
    private String timeSchedule;

    /** 创建人 ID */
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
