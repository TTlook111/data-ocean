package com.dataocean.module.user.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门树形结构视图对象。
 * <p>
 * 用于前端展示组织架构树，包含子部门列表以支持递归渲染。
 * 接口返回时已组装为树形结构，前端无需二次处理。
 * </p>
 *
 * @author dataocean
 */
@Data
@Builder
public class DepartmentTreeVO {

    /** 部门ID */
    private Long id;

    /** 上级部门ID */
    private Long parentId;

    /** 部门名称 */
    private String deptName;

    /** 部门编码 */
    private String deptCode;

    /** 排序序号 */
    private Integer sortOrder;

    /** 子部门列表，默认为空集合 */
    @Builder.Default
    private List<DepartmentTreeVO> children = new ArrayList<>();
}
