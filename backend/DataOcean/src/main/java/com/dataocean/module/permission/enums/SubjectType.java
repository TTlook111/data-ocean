package com.dataocean.module.permission.enums;

/**
 * 授权主体类型枚举
 * <p>
 * 定义数据源访问授权和行列级策略的主体维度，
 * 支持用户、角色、部门三种维度的权限配置。
 * </p>
 *
 * @author dataocean
 */
public enum SubjectType {

    /** 用户维度 */
    USER,
    /** 角色维度 */
    ROLE,
    /** 部门维度 */
    DEPARTMENT
}
