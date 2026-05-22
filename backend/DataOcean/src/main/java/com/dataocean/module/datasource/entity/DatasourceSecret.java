package com.dataocean.module.datasource.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源凭证实体类
 * <p>
 * 对应数据库表 datasource_secret，存储数据源的连接凭证信息，
 * 密码使用 AES-256-GCM 加密存储，确保敏感信息安全。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("datasource_secret")
public class DatasourceSecret {

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联的数据源 ID */
    private Long datasourceId;
    /** 数据库连接用户名 */
    private String username;
    /** AES-GCM 加密后的密码（Base64 编码） */
    private String encryptedPassword;
    /** 加密版本号，用于密钥轮换时区分加密方式 */
    private Integer encryptVersion;
    /** 创建时间，自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    /** 更新时间，自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
