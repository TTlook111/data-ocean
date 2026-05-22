package com.dataocean.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统部门实体类，对应数据库表 sys_department。
 * <p>
 * 存储组织架构中的部门信息，支持树形层级结构（通过 parentId 自关联）。
 * 用户通过 departmentId 归属到某个部门。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("sys_department")
public class SysDepartment {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 上级部门ID，顶级部门为 0 或 null */
    private Long parentId;

    /** 部门名称 */
    private String deptName;

    /** 部门编码，唯一标识 */
    private String deptCode;

    /** 排序序号，数值越小越靠前 */
    private Integer sortOrder;

    /** 部门状态：1-启用，0-禁用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
