package com.dataocean.module.permission.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源访问授权视图对象
 *
 * @author dataocean
 */
@Data
public class DatasourcePermissionVO {

    private Long id;
    private Long datasourceId;
    private String datasourceName;
    private String subjectType;
    private Long subjectId;
    /** 主体名称（用户名/角色名/部门名） */
    private String subjectName;
    private Boolean canQuery;
    private Boolean canExport;
    private Boolean canViewSql;
    private String accessEffect;
    private LocalDateTime grantedAt;
    private LocalDateTime expiresAt;
}
