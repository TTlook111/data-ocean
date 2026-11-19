package com.dataocean.module.access.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 按当前部门版本回读初始数据源配置及直属成员。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentAccessVO {

    /**
     * 稳定部门编号。
     */
    private String id;

    /**
     * 部门名称。
     */
    private String name;

    /**
     * 用于复核配置和成员变更的部门版本。
     */
    private int version;

    /**
     * 恢复后配置仍待复核。
     */
    private boolean reviewRequired;

    /**
     * 受影响的直属人员数量。
     */
    private long memberCount;

    /**
     * 现有配置和当下适用的规则。
     */
    private List<AccessScopeVO> sources;
}
