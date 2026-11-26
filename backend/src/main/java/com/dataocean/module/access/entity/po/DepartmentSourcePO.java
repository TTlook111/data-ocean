package com.dataocean.module.access.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 部门初始访问配置的生效区间。
 */
@Data
@TableName("access_department_source")
public class DepartmentSourcePO {

    /**
     * 配置来源编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 配置归属的直属部门。
     */
    private Long departmentId;

    /**
     * 目标数据源。
     */
    private Long sourceId;

    /**
     * 配置当时的规则全文快照。
     */
    private String ruleSnapshot;

    /**
     * 配置生效时间，UTC。
     */
    private LocalDateTime startedAt;

    /**
     * 曾生效配置的结束时间，UTC。
     */
    private LocalDateTime endedAt;
}
