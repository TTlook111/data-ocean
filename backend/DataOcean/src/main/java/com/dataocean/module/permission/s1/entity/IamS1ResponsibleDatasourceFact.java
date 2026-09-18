package com.dataocean.module.permission.s1.entity;

import lombok.Data;

/** 某用户角色绑定负责的数据源事实。负责源只限制后台工作范围。 */
@Data
public class IamS1ResponsibleDatasourceFact {
    private Long userRoleId;
    private Long datasourceId;
    private String datasourceName;
    private Integer datasourceStatus;
}
