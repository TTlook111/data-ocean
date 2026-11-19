package com.dataocean.module.access.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 授权表内同一分页的申请授权或部门初始来源。
 */
@Data
@AllArgsConstructor
public class GrantDirectoryVO {

    /**
     * 带来源类型的稳定列表标识。
     */
    private String id;

    /**
     * 申请审批形成的来源，仅申请行存在。
     */
    private AccessGrantVO request;

    /**
     * 实际组织关系形成的来源，仅部门行存在。
     */
    private DepartmentGrantVO department;
}
