package com.dataocean.module.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源访问授权实体类
 * <p>
 * 对应数据库表 datasource_access，记录用户对数据源的访问权限授予信息，
 * 包括被授权用户、授权人、授权时间和过期时间。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("datasource_access")
public class DatasourceAccess {

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 数据源 ID */
    private Long datasourceId;
    /** 被授权用户 ID */
    private Long userId;
    /** 授权操作人 ID */
    private Long grantedBy;
    /** 授权时间 */
    private LocalDateTime grantedAt;
    /** 授权过期时间，为 null 表示永不过期 */
    private LocalDateTime expiresAt;
}
