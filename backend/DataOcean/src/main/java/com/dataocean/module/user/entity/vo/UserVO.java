package com.dataocean.module.user.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户列表/详情返回视图对象。
 * <p>
 * 用于管理端用户列表展示，包含用户基本信息、所属部门名称、
 * 角色列表等聚合数据，避免前端多次请求。
 * </p>
 *
 * @author dataocean
 */
@Data
@Builder
public class UserVO {

    /** 用户ID */
    private Long id;

    /** 登录用户名 */
    private String username;

    /** 真实姓名 */
    private String realName;

    /** 电子邮箱 */
    private String email;

    /** 手机号码 */
    private String phone;

    /** 所属部门ID */
    private Long departmentId;

    /** 所属部门名称（冗余展示用） */
    private String departmentName;

    /** 用户拥有的角色ID列表 */
    private List<Long> roleIds;

    /** 用户拥有的角色名称列表 */
    private List<String> roleNames;

    /** 用户拥有的角色编码列表 */
    private List<String> roleCodes;

    /** 账号状态：1-正常，2-禁用，3-锁定 */
    private Integer status;

    /** 最后登录时间 */
    private LocalDateTime lastLoginAt;

    /** 账号创建时间 */
    private LocalDateTime createdAt;
}
