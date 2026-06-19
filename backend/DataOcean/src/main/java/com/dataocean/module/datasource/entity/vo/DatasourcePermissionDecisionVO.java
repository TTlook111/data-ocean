package com.dataocean.module.datasource.entity.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Data source level permission decision after merging department, role and user grants.
 */
@Data
public class DatasourcePermissionDecisionVO {

    private Long datasourceId;
    private Long userId;
    private boolean canQuery;
    private boolean canExport;
    private boolean canViewSql;
    private String decisionSource = "NONE";
    private Long departmentId;
    private Long effectiveDepartmentId;
    private List<Long> roleIds = new ArrayList<>();
    private Long userGrantId;
    private String accessEffect = "NONE";
}
