package com.dataocean.module.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统配置实体类。
 * <p>
 * 对应数据库表 sys_config，用于存储系统级键值对配置项，
 * 支持按 key 前缀分组管理（如 security.* 、query.* 等）。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("sys_config")
public class SysConfig {

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置键（唯一标识），如 "security.login.maxRetry" */
    private String configKey;

    /** 配置值，以字符串形式存储 */
    private String configValue;

    /** 配置项描述说明 */
    private String description;

    /** 创建时间，由 MyBatis-Plus 自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间，由 MyBatis-Plus 在插入和更新时自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
