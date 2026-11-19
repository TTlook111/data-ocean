package com.dataocean.module.access.entity.vo;

import com.dataocean.module.iam.entity.vo.UserOptionVO;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 一位直属人员在一笔部门配置有效区间内的访问来源。
 */
@Data
@AllArgsConstructor
public class DepartmentGrantVO {

    /**
     * 部门配置和成员关系组成的稳定复合编号。
     */
    private String id;

    /**
     * 实际直属部门编号。
     */
    private String departmentId;

    /**
     * 部门名称。
     */
    private String departmentName;

    /**
     * 实际受影响人员。
     */
    private UserOptionVO grantee;

    /**
     * 授予的稳定数据源。
     */
    private AccessSourceOptionVO source;

    /**
     * 当前有效、暂停或已结束。
     */
    private String state;

    /**
     * 配置与直属关系同时成立的 UTC 时间。
     */
    private Instant startedAt;

    /**
     * 配置或成员关系较早结束的 UTC 时间。
     */
    private Instant endedAt;

    /**
     * 有效时采用当前规则，否则读取当时配置快照。
     */
    private List<OutputRuleVO> rules;
}
