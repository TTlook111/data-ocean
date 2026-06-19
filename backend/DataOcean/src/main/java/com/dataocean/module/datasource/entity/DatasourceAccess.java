package com.dataocean.module.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源访问授权实体类
 * <p>
 * 对应数据库表 datasource_access，记录主体（用户/角色/部门）对数据源的访问权限，
 * 包括查询、导出、查看SQL等细粒度控制。
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
    /** 授权主体类型: USER/ROLE/DEPARTMENT */
    private String subjectType;
    /** 授权主体 ID（用户ID/角色ID/部门ID） */
    private Long subjectId;
    /** 授权操作人 ID */
    private Long grantedBy;
    /** 授权时间 */
    private LocalDateTime grantedAt;
    /** 授权过期时间，为 null 表示永不过期 */
    private LocalDateTime expiresAt;
    /** 是否允许查询 */
    private Boolean canQuery;
    /** 是否允许导出 */
    private Boolean canExport;
    /** 是否允许查看生成的SQL */
    private Boolean canViewSql;
    private String accessEffect;
}
